package com.smwu.backend.academy.service;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.dto.AcademyDtos.AcademyResponse;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.user.domain.UserRole;
import com.smwu.backend.user.repository.UserRepository;
import com.smwu.backend.workspace.service.CurrentAcademy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 학원 로고 (설계서 8). 원장만 바꿀 수 있고, 모든 시험지 머리글에 들어간다.
 * 확장자가 아니라 실제로 PNG/JPG로 읽히는지 확인하고, 가로 600px를 넘으면 비율을 유지해 줄인 뒤 PNG로 저장한다.
 */
@Service
@RequiredArgsConstructor
public class LogoService {

    static final long MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_WIDTH = 600;
    private static final Set<String> FORMATS = Set.of("png", "jpeg");
    private static final String STORAGE_DIR = "logos";

    private final AcademyRepository academyRepository;
    private final UserRepository userRepository;
    private final FileStorage fileStorage;
    private final CurrentAcademy currentAcademy;

    /** 시험지 PDF용 로고 (data URI). 로고가 없으면 비어 있다 */
    public record Logo(byte[] png, int width, int height) {

        public String dataUri() {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        }
    }

    @Transactional
    public AcademyResponse upload(MultipartFile file) {
        currentAcademy.requireOwner();
        Academy academy = getAcademy(currentAcademy.academyId());
        BufferedImage image = resize(readImage(file));
        String path = fileStorage.save(STORAGE_DIR + "/" + academy.getId(), "png", toPng(image));
        String previous = academy.getLogoPath();
        academy.changeLogo(path, image.getWidth(), image.getHeight());
        afterCompletion(path, previous);
        return AcademyResponse.of(academy, userRepository.countByAcademyIdAndRole(academy.getId(), UserRole.TEACHER));
    }

    @Transactional
    public void delete() {
        currentAcademy.requireOwner();
        Academy academy = getAcademy(currentAcademy.academyId());
        String previous = academy.getLogoPath();
        academy.removeLogo();
        afterCompletion(null, previous);
    }

    /** 미리보기 (학원 강사 누구나). 로고가 없으면 404 */
    @Transactional(readOnly = true)
    public byte[] currentLogo() {
        Academy academy = getAcademy(currentAcademy.academyId());
        if (!academy.hasLogo()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "등록된 로고가 없습니다.");
        }
        return fileStorage.read(academy.getLogoPath());
    }

    /** PDF 출력(#18)에서 쓰는 로고 */
    @Transactional(readOnly = true)
    public Optional<Logo> logoOf(Long academyId) {
        return academyRepository.findById(academyId)
                .filter(Academy::hasLogo)
                .map(a -> new Logo(fileStorage.read(a.getLogoPath()), a.getLogoWidth(), a.getLogoHeight()));
    }

    static BufferedImage readImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "로고 파일을 올려 주세요.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "로고는 2MB 이하만 올릴 수 있습니다.");
        }
        try {
            byte[] bytes = file.getBytes();
            try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
                if (readers == null || !readers.hasNext()
                        || !FORMATS.contains(readers.next().getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(ErrorCode.INVALID_FILE, "로고는 PNG 또는 JPG 이미지만 올릴 수 있습니다.");
                }
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_FILE, "이미지를 읽을 수 없습니다. 손상되지 않은 PNG 또는 JPG를 올려 주세요.");
            }
            return image;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "이미지를 읽을 수 없습니다. 손상되지 않은 PNG 또는 JPG를 올려 주세요.");
        }
    }

    /** 가로 600px 초과면 비율 유지 축소. 투명 배경을 살리려고 ARGB로 그린다 */
    static BufferedImage resize(BufferedImage image) {
        int width = Math.min(image.getWidth(), MAX_WIDTH);
        int height = Math.max(1, (int) Math.round(image.getHeight() * (width / (double) image.getWidth())));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("로고를 PNG로 저장하지 못했습니다.", e);
        }
    }

    /** 커밋되면 이전 파일, 롤백되면 새 파일을 지운다 */
    private void afterCompletion(String saved, String previous) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String unused = status == STATUS_COMMITTED ? previous : saved;
                if (unused != null) {
                    fileStorage.delete(unused);
                }
            }
        });
    }

    private Academy getAcademy(Long academyId) {
        return academyRepository.findById(academyId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
