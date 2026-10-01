package com.smwu.backend.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF를 페이지 이미지로 바꾼다. 텍스트 레이어가 있는 PDF는 LLM에 텍스트만 전달되어 밑줄·박스가 사라지므로,
 * 페이지 이미지를 함께 보내 모델이 밑줄을 직접 보게 한다. (이슈 #5 실험 결과)
 */
public final class PdfPageImages {

    /** A4 기준 약 1240x1754px. 시험지의 작은 글씨와 밑줄이 읽히는 해상도 */
    public static final int DEFAULT_DPI = 150;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int MIN_TEXT_CHARS = 200;

    private PdfPageImages() {
    }

    /** 스캔본처럼 글자 정보가 없는 PDF인지 판단한다 */
    public static boolean hasTextLayer(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            return text.strip().length() >= MIN_TEXT_CHARS;
        } catch (IOException e) {
            throw new UncheckedIOException("PDF를 읽지 못했습니다.", e);
        }
    }

    /** 페이지마다 JPEG 이미지로 렌더링한다 (페이지 순서대로) */
    public static List<byte[]> renderJpeg(byte[] pdf, int dpi) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> pages = new ArrayList<>(document.getNumberOfPages());
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                pages.add(toJpeg(renderer.renderImageWithDPI(i, dpi, ImageType.RGB)));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 페이지를 이미지로 변환하지 못했습니다.", e);
        }
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(image, null, null), param);
            imageOut.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
