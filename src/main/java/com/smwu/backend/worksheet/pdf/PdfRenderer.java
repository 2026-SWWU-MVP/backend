package com.smwu.backend.worksheet.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * XHTML → PDF (OpenHTMLtoPDF). 한글은 폰트를 등록하지 않으면 깨지므로 나눔명조·나눔고딕 정적 TTF를 등록한다 (설계서 9).
 * 폰트 파일은 처음 한 번 읽어 메모리에 두고, 필요한 글자만 PDF에 넣는다(subset).
 */
@Component
public class PdfRenderer {

    public static final String SERIF = "NanumMyeongjo";
    public static final String SANS = "NanumGothic";

    private record FontFile(String family, int weight, byte[] bytes) {
    }

    private final List<FontFile> fonts = List.of(
            font(SERIF, 400, "fonts/NanumMyeongjo-Regular.ttf"),
            font(SERIF, 700, "fonts/NanumMyeongjo-Bold.ttf"),
            font(SANS, 400, "fonts/NanumGothic-Regular.ttf"),
            font(SANS, 700, "fonts/NanumGothic-Bold.ttf"));

    public byte[] render(String xhtml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder().useFastMode();
            for (FontFile f : fonts) {
                builder.useFont(() -> new ByteArrayInputStream(f.bytes()), f.family(), f.weight(), FontStyle.NORMAL, true);
            }
            builder.withHtmlContent(xhtml, null).toStream(out).run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF를 만들지 못했습니다.", e);
        }
    }

    private static FontFile font(String family, int weight, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new FontFile(family, weight, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 폰트를 읽지 못했습니다: " + path, e);
        }
    }
}
