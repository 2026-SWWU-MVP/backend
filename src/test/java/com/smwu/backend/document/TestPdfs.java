package com.smwu.backend.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** 테스트용 PDF 생성 */
public final class TestPdfs {

    private static final String LINE = "The students read a short passage about city parks and answered five questions.";

    private TestPdfs() {
    }

    /** 페이지마다 영어 문장이 있는 A4 PDF (텍스트 레이어 있음) */
    public static byte[] textPdf(int pages) {
        return build(pages, true);
    }

    /** 글자 없는 A4 PDF (스캔본처럼 텍스트 레이어 없음) */
    public static byte[] blankPdf(int pages) {
        return build(pages, false);
    }

    private static byte[] build(int pages, boolean withText) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                if (withText) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 10);
                        stream.setLeading(14);
                        stream.newLineAtOffset(50, 780);
                        for (int line = 0; line < 5; line++) {
                            stream.showText(LINE);
                            stream.newLine();
                        }
                        stream.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
