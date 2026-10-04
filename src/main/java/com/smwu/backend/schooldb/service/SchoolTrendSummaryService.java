package com.smwu.backend.schooldb.service;

import com.smwu.backend.schooldb.domain.SchoolTrendSummary;
import com.smwu.backend.schooldb.dto.SchoolTrendResponse;
import com.smwu.backend.schooldb.dto.SchoolTrendSummaryResponse;
import com.smwu.backend.schooldb.repository.SchoolTrendSummaryRepository;
import com.smwu.backend.schooldb.service.SchoolTrendService.Snapshot;
import com.smwu.backend.schooldb.service.SchoolTrendSummarizer.Summary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 학교 경향 요약. 요약에 넣을 통계 입력이 지난번과 같으면 캐시를 돌려주고, 바뀌었을 때만 LLM을 부른다.
 * LLM 호출 동안에는 트랜잭션을 열어 두지 않는다 (읽기 → LLM → 저장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolTrendSummaryService {

    private final SchoolTrendService trendService;
    private final SchoolTrendSummarizer summarizer;
    private final SchoolTrendSummaryRepository summaryRepository;
    private final PlatformTransactionManager transactionManager;

    public SchoolTrendSummaryResponse summary(Long schoolId, int grade) {
        return summarize(trendService.snapshot(schoolId, grade));
    }

    public SchoolTrendSummaryResponse forWorkspace(Long workspaceId) {
        return summarize(trendService.snapshotForWorkspace(workspaceId));
    }

    private SchoolTrendSummaryResponse summarize(Snapshot snapshot) {
        String basis = SchoolTrendResponse.of(snapshot.school().getId(), snapshot.school().getName(), snapshot.grade(),
                snapshot.contributorCount(), snapshot.latestExam(), snapshot.trend()).basis();
        if (snapshot.trend().examCount() == 0) {
            return SchoolTrendSummaryResponse.empty(snapshot.school().getId(), snapshot.grade(), basis);
        }
        String key = basisKey(snapshot, basis);
        Optional<SchoolTrendSummary> cached = summaryRepository.findBySchoolIdAndGrade(snapshot.school().getId(), snapshot.grade())
                .filter(s -> key.equals(s.getBasisKey()));
        if (cached.isPresent()) {
            return SchoolTrendSummaryResponse.of(cached.get(), basis, true);
        }

        Summary summary = summarizer.summarize(snapshot.target(), basis, snapshot.trend(), snapshot.rounds(),
                snapshot.contributorsByRound());
        SchoolTrendSummary saved = new TransactionTemplate(transactionManager).execute(status -> {
            SchoolTrendSummary entity = summaryRepository.findBySchoolIdAndGrade(snapshot.school().getId(), snapshot.grade())
                    .orElseGet(() -> new SchoolTrendSummary(snapshot.school().getId(), snapshot.grade()));
            entity.update(key, snapshot.trend().examCount(), summary.headline(), summary.points(), summary.prepTips(), summary.model());
            return summaryRepository.save(entity);
        });
        log.info("학교 경향 요약 생성 {} (기출 {}회분, 시도 {}회)", snapshot.target(), snapshot.trend().examCount(), summary.attempts());
        return SchoolTrendSummaryResponse.of(saved, basis, false);
    }

    /** 요약 LLM에 들어가는 입력 그대로의 해시 → 입력이 같으면 다시 만들 필요가 없다 */
    static String basisKey(Snapshot snapshot, String basis) {
        String input = snapshot.target() + "\n" + basis + "\n" + SchoolTrendSummarizer.describeTrend(snapshot.trend()) + "\n"
                + SchoolTrendSummarizer.describeRounds(snapshot.rounds(), snapshot.contributorsByRound()) + "\n"
                + String.join("\n", snapshot.trend().highlights());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
