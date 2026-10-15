package com.smwu.backend.demo;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.domain.AcademyPlan;
import com.smwu.backend.academy.domain.InviteCode;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.academy.repository.InviteCodeRepository;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.material.repository.MaterialRepository;
import com.smwu.backend.material.repository.PassageRepository;
import com.smwu.backend.material.service.PassageSplitter;
import com.smwu.backend.material.service.PassageSplitter.SplitPassage;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import com.smwu.backend.user.repository.UserRepository;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.SchoolRepository;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import com.smwu.backend.workspace.service.SchoolSeeder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시연용 데이터 (#24). {@code demo} 프로필로 실행하면 한 번만 만든다 (원장 계정이 이미 있으면 건너뜀).
 * <ul>
 *   <li>학원 "눈송어학원" (로고 포함), 원장 demo, 강사 teacher1·teacher2 — 비밀번호는 모두 {@link #PASSWORD}</li>
 *   <li>초대 코드 4개: 사용 가능 FNRD26 · 사용됨 WELCM3(박강사) · 만료 XPRD47 · 취소 CNCL58</li>
 *   <li>워크스페이스 건대부고 1학년, 압구정고 1학년</li>
 *   <li>건대부고 1학년 시험범위 텍스트 "교과서 2과" (지문 2개)</li>
 * </ul>
 * 화면 개발·시연용 더미 데이터 (#62, {@link DemoSampleData})
 * <ul>
 *   <li>압구정고 1학년: 기출 3회분 → 프로필 v2 확정 → 생성·검수 → 시험지까지 끝난 상태 (시험범위 "교과서 2과" 포함)</li>
 *   <li>현대고 2학년: 기출 1회분 + 프로필 v1 초안</li>
 *   <li>다른 학원 "한빛영어학원"(원장 hanbit): 압구정고 1학년 기출 1회분 → 학교 DB가 학원 2곳 기준이 된다</li>
 * </ul>
 * 기출 PDF는 저작권 때문에 저장소에 넣지 않으므로 시연 3단계에서 직접 올린다. 시연 순서는 docs/DEMO.md.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder {

    public static final String OWNER_LOGIN_ID = "demo";
    public static final String PASSWORD = "1234";
    static final String ACADEMY_NAME = "눈송어학원";
    /** 예전 데모 학원명. 이 이름 그대로인 기존 데모 DB는 새 이름·로고로 바꾼다 (#70) */
    static final String LEGACY_ACADEMY_NAME = "파인로드영어";
    static final String OTHER_OWNER_LOGIN_ID = "hanbit";
    static final String OTHER_ACADEMY_NAME = "한빛영어학원";

    /** Mock LLM 응답과 맞는 지문이라 LLM 비용 없이도 문제 생성이 PASSED로 나온다 */
    static final String PASSAGES = """
            # Bringing New Life to Old Cities
            As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area. Let's take a look at two projects in the Netherlands to find out what successful urban revitalization looks like.
            ---
            # Crime and Budget
            For years, the neighborhood was known for its high crime rates. However, having a limited budget, the government was unable to do so and had to come up with a new plan.
            """;

    private final UserRepository userRepository;
    private final AcademyRepository academyRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final SchoolRepository schoolRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MaterialRepository materialRepository;
    private final PassageRepository passageRepository;
    private final DemoSampleData sampleData;
    private final FileStorage fileStorage;
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    @Order(SchoolSeeder.ORDER + 1)
    @Transactional
    public void seed() {
        if (userRepository.existsByLoginId(OWNER_LOGIN_ID)) {
            log.info("데모 데이터가 이미 있어 건너뜀 (원장 {})", OWNER_LOGIN_ID);
            renameLegacyAcademy();
            addInvitesIfMissing();
            return;
        }
        Academy academy = academyRepository.save(new Academy(ACADEMY_NAME, AcademyPlan.BASIC));
        byte[] logo = logo();
        academy.changeLogo(fileStorage.save("logos/" + academy.getId(), "png", logo), LOGO_WIDTH, LOGO_HEIGHT);

        User owner = user(OWNER_LOGIN_ID, "김원장", academy, UserRole.OWNER);
        User teacher1 = user("teacher1", "이강사", academy, UserRole.TEACHER);
        User teacher2 = user("teacher2", "박강사", academy, UserRole.TEACHER);
        invites(academy, owner, teacher2);

        School konkuk = school("건국대학교사범대학부속고등학교", "서울 광진구", List.of("건대부고", "건국대부고"));
        School apgujeong = school("압구정고등학교", "서울 강남구", List.of("압구정고"));
        School hyundai = school("현대고등학교", "서울 강남구", List.of("현대고"));
        Workspace main = workspaceRepository.save(new Workspace(academy.getId(), konkuk.getId(), 1, owner.getId()));
        Workspace completed = workspaceRepository.save(new Workspace(academy.getId(), apgujeong.getId(), 1, owner.getId()));
        Workspace inReview = workspaceRepository.save(new Workspace(academy.getId(), hyundai.getId(), 2, owner.getId()));

        textbook(main, owner);
        List<Passage> textbook = textbook(completed, owner);

        Academy other = academyRepository.save(new Academy(OTHER_ACADEMY_NAME, AcademyPlan.BASIC));
        User otherOwner = user(OTHER_OWNER_LOGIN_ID, "최원장", other, UserRole.OWNER);
        sampleData.otherAcademy(workspaceRepository.save(new Workspace(other.getId(), apgujeong.getId(), 1, otherOwner.getId())),
                otherOwner);
        sampleData.completed(completed, owner, teacher1, textbook);
        sampleData.inReview(inReview, teacher1);
        log.info("데모 데이터 생성: 학원 {}, 원장 {} / 강사 teacher1, teacher2 (비밀번호 {}), 워크스페이스 3개 (더미 데이터: 압구정고 1학년 완료, 현대고 2학년 검토 중)", ACADEMY_NAME,
                OWNER_LOGIN_ID, PASSWORD);
    }

    /** 예전 이름("파인로드영어")으로 만든 데모 DB도 초기화 없이 새 이름·로고로 (강사가 직접 바꾼 이름은 그대로 둔다) */
    private void renameLegacyAcademy() {
        Long academyId = userRepository.findByLoginId(OWNER_LOGIN_ID).orElseThrow().getAcademyId();
        if (academyId == null) {
            return;
        }
        academyRepository.findById(academyId)
                .filter(academy -> LEGACY_ACADEMY_NAME.equals(academy.getName()))
                .ifPresent(academy -> {
                    academy.rename(ACADEMY_NAME);
                    academy.changeLogo(fileStorage.save("logos/" + academy.getId(), "png", logo()), LOGO_WIDTH, LOGO_HEIGHT);
                    log.info("기존 데모 학원 이름을 {} → {}로 변경", LEGACY_ACADEMY_NAME, ACADEMY_NAME);
                });
    }

    /** 초대 코드(#66)보다 먼저 만든 데모 DB에도 초기화 없이 초대 코드를 채운다 (자동 배포는 DB를 비우지 않음) */
    private void addInvitesIfMissing() {
        User owner = userRepository.findByLoginId(OWNER_LOGIN_ID).orElseThrow();
        Long academyId = owner.getAcademyId();
        if (academyId == null || !inviteCodeRepository.findByAcademyIdOrderByIdDesc(academyId).isEmpty()) {
            return;
        }
        userRepository.findByLoginId("teacher2").ifPresent(teacher2 -> {
            invites(academyRepository.findById(academyId).orElseThrow(), owner, teacher2);
            log.info("기존 데모 데이터에 초대 코드 4개 추가");
        });
    }

    /** 학원 관리 화면용 초대 코드: 사용 가능 · 사용됨(박강사) · 만료 · 취소 (#66) */
    private void invites(Academy academy, User owner, User usedBy) {
        LocalDateTime now = LocalDateTime.now();
        inviteCodeRepository.save(new InviteCode(academy.getId(), "FNRD26", owner.getId(), now));
        InviteCode used = new InviteCode(academy.getId(), "WELCM3", owner.getId(), now.minusDays(3));
        used.use(usedBy.getId(), now.minusDays(2));
        inviteCodeRepository.save(used);
        inviteCodeRepository.save(new InviteCode(academy.getId(), "XPRD47", owner.getId(), now.minusDays(10)));
        InviteCode canceled = new InviteCode(academy.getId(), "CNCL58", owner.getId(), now.minusDays(1));
        canceled.cancel();
        inviteCodeRepository.save(canceled);
    }

    private List<Passage> textbook(Workspace workspace, User owner) {
        Material material = materialRepository.save(Material.text(workspace.getId(), "교과서 2과", owner.getId()));
        List<SplitPassage> split = PassageSplitter.splitText(PASSAGES);
        List<Passage> passages = new ArrayList<>();
        for (int i = 0; i < split.size(); i++) {
            SplitPassage p = split.get(i);
            passages.add(passageRepository.save(new Passage(material.getId(), workspace.getId(), i + 1, p.title(), p.sourceLabel(), p.text())));
        }
        return passages;
    }

    private User user(String loginId, String name, Academy academy, UserRole role) {
        User user = new User(loginId, passwordEncoder.encode(PASSWORD), name);
        user.joinAcademy(academy.getId(), role);
        return userRepository.save(user);
    }

    /** 학교 seed(SchoolSeeder) 다음에 실행되지만, 시드 파일이 바뀌어도 되도록 없으면 만든다 */
    private School school(String name, String region, List<String> aliases) {
        String key = School.normalize(name);
        return schoolRepository.findAll().stream().filter(s -> s.getNormalizedName().equals(key)).findFirst()
                .orElseGet(() -> schoolRepository.save(new School(name, region, aliases)));
    }

    static final int LOGO_WIDTH = 480;
    static final int LOGO_HEIGHT = 120;

    /** 시연용 로고: 투명 배경에 동그라미 엠블럼 + 학원명 (나눔고딕 Bold) */
    static byte[] logo() {
        BufferedImage image = new BufferedImage(LOGO_WIDTH, LOGO_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try (InputStream in = new ClassPathResource("fonts/NanumGothic-Bold.ttf").getInputStream()) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(28, 78, 160));
            g.fillOval(8, 12, 96, 96);
            g.setColor(Color.WHITE);
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            // 엠블럼 가운데에 학원명 첫 글자
            g.setFont(font.deriveFont(52f));
            String initial = ACADEMY_NAME.substring(0, 1);
            int initialWidth = g.getFontMetrics().stringWidth(initial);
            g.drawString(initial, 56 - initialWidth / 2, 80);
            g.setColor(new Color(28, 78, 160));
            g.setFont(font.deriveFont(56f));
            g.drawString(ACADEMY_NAME, 122, 82);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException | java.awt.FontFormatException e) {
            throw new IllegalStateException("데모 로고를 만들지 못했습니다.", e);
        } finally {
            g.dispose();
        }
    }
}
