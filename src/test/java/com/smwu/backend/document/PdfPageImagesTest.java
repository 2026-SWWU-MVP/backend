package com.smwu.backend.document;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPageImagesTest {

    @Test
    void 텍스트_레이어_유무를_구분한다() {
        assertThat(PdfPageImages.hasTextLayer(TestPdfs.textPdf(1))).isTrue();
        assertThat(PdfPageImages.hasTextLayer(TestPdfs.blankPdf(1))).isFalse();
    }

    @Test
    void 페이지마다_지정한_해상도의_JPEG로_렌더링한다() throws IOException {
        List<byte[]> pages = PdfPageImages.renderJpeg(TestPdfs.textPdf(2), 150);

        assertThat(pages).hasSize(2);
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
        // A4(595x842pt)를 150dpi로 렌더링하면 약 1240x1754px
        assertThat(first.getWidth()).isBetween(1235, 1245);
        assertThat(first.getHeight()).isBetween(1750, 1760);
    }
}
