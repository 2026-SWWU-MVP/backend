package com.smwu.backend.support;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.domain.AcademyPlan;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import com.smwu.backend.user.repository.UserRepository;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.SchoolRepository;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트용 워크스페이스·학원 준비. 데이터 격리(#10) 이후 하위 리소스 API는 실제 내 학원 워크스페이스가 있어야 한다.
 * 다른 학원 사용자로 요청하려면 {@link #newAcademy()}의 userId를 X-User-Id 헤더에 넣는다.
 */
@Component
public class TestWorkspaces {

    private static final AtomicLong SEQ = new AtomicLong();

    private final TestUserMockMvcCustomizer testUsers;
    private final SchoolRepository schoolRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AcademyRepository academyRepository;
    private final UserRepository userRepository;

    public TestWorkspaces(TestUserMockMvcCustomizer testUsers, SchoolRepository schoolRepository, WorkspaceRepository workspaceRepository,
                          AcademyRepository academyRepository, UserRepository userRepository) {
        this.testUsers = testUsers;
        this.schoolRepository = schoolRepository;
        this.workspaceRepository = workspaceRepository;
        this.academyRepository = academyRepository;
        this.userRepository = userRepository;
    }

    /** @param userId 이 학원 원장 (X-User-Id에 넣을 값) */
    public record Member(long userId, long academyId) {
    }

    /** 기본 테스트 사용자 학원에 새 학교 1학년 워크스페이스 */
    public long create() {
        return create(newSchool(), 1);
    }

    /** 기본 테스트 사용자 학원에 이 학교·학년 워크스페이스 */
    public long create(long schoolId, int grade) {
        User user = testUsers.testUser();
        return workspaceRepository.save(new Workspace(user.getAcademyId(), schoolId, grade, user.getId())).getId();
    }

    /** 다른 학원 원장 */
    public Member newAcademy() {
        long n = SEQ.incrementAndGet();
        Academy academy = academyRepository.save(new Academy("다른학원" + n, AcademyPlan.BASIC));
        User owner = new User("other" + n + System.nanoTime() % 10000, "{test}", "다른원장" + n);
        owner.joinAcademy(academy.getId(), UserRole.OWNER);
        return new Member(userRepository.save(owner).getId(), academy.getId());
    }

    /** 기본 테스트 사용자 학원의 강사 (userId) */
    public long newTeacher() {
        long n = SEQ.incrementAndGet();
        User teacher = new User("teacher" + n + System.nanoTime() % 10000, "{test}", "같은학원강사" + n);
        teacher.joinAcademy(testUsers.testUser().getAcademyId(), UserRole.TEACHER);
        return userRepository.save(teacher).getId();
    }

    /** 학원 소속 전 사용자 (userId) */
    public long newUserWithoutAcademy() {
        long n = SEQ.incrementAndGet();
        return userRepository.save(new User("noacademy" + n + System.nanoTime() % 10000, "{test}", "소속없음" + n)).getId();
    }

    public long create(Member member, long schoolId, int grade) {
        return workspaceRepository.save(new Workspace(member.academyId(), schoolId, grade, member.userId())).getId();
    }

    public long newSchool() {
        return schoolRepository.save(new School("테스트학교" + SEQ.incrementAndGet() + "고등학교", null, List.of())).getId();
    }
}
