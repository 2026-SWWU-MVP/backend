package com.smwu.backend.worksheet.service;

import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.user.service.UserNames;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import com.smwu.backend.worksheet.domain.Worksheet;
import com.smwu.backend.worksheet.domain.WorksheetItem;
import com.smwu.backend.worksheet.dto.WorksheetDtos.CreateWorksheetRequest;
import com.smwu.backend.worksheet.dto.WorksheetDtos.Item;
import com.smwu.backend.worksheet.dto.WorksheetDtos.Section;
import com.smwu.backend.worksheet.dto.WorksheetDtos.UpdateWorksheetRequest;
import com.smwu.backend.worksheet.dto.WorksheetDtos.WorksheetResponse;
import com.smwu.backend.worksheet.dto.WorksheetDtos.WorksheetSummary;
import com.smwu.backend.worksheet.repository.WorksheetItemRepository;
import com.smwu.backend.worksheet.repository.WorksheetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 시험지 구성 (#16). 채택(ACCEPTED)한 문항만 넣을 수 있고, 같은 지문의 문항은 한데 모아 지문마다 번호를 1부터 매긴다.
 * 학원 안의 모든 강사가 목록을 본다 (데이터 격리는 워크스페이스 기준).
 */
@Service
@RequiredArgsConstructor
public class WorksheetService {

    private final WorksheetRepository worksheetRepository;
    private final WorksheetItemRepository itemRepository;
    private final ProblemRepository problemRepository;
    private final WorkspaceAccessChecker accessChecker;
    private final UserNames userNames;

    @Transactional
    public WorksheetResponse create(Long workspaceId, CreateWorksheetRequest request) {
        accessChecker.check(workspaceId);
        List<Problem> ordered = orderedProblems(workspaceId, request.problemIds());
        Worksheet worksheet = worksheetRepository.save(new Worksheet(workspaceId, request.title().strip(),
                blankToNull(request.headerText()), request.showLogo() == null || request.showLogo(),
                CurrentUserContext.userIdOrNull()));
        saveItems(worksheet.getId(), ordered);
        return toResponse(worksheet, ordered);
    }

    @Transactional(readOnly = true)
    public List<WorksheetSummary> list(Long workspaceId) {
        accessChecker.check(workspaceId);
        List<Worksheet> worksheets = worksheetRepository.findByWorkspaceIdOrderByIdDesc(workspaceId);
        Map<Long, Long> counts = new HashMap<>();
        if (!worksheets.isEmpty()) {
            itemRepository.countByWorksheet(worksheets.stream().map(Worksheet::getId).toList())
                    .forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        }
        Map<Long, String> names = userNames.names(worksheets.stream().map(Worksheet::getCreatedBy).toList());
        return worksheets.stream()
                .map(w -> new WorksheetSummary(w.getId(), w.getWorkspaceId(), w.getTitle(), w.getHeaderText(), w.isShowLogo(),
                        counts.getOrDefault(w.getId(), 0L), w.getCreatedBy(), names.get(w.getCreatedBy()), w.getCreatedAt(),
                        w.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorksheetResponse get(Long worksheetId) {
        Worksheet worksheet = getWorksheet(worksheetId);
        return toResponse(worksheet, problemsOf(worksheet));
    }

    @Transactional
    public WorksheetResponse update(Long worksheetId, UpdateWorksheetRequest request) {
        Worksheet worksheet = getWorksheet(worksheetId);
        worksheet.update(request.title() == null ? null : request.title().strip(), request.headerText(), request.showLogo());
        List<Problem> problems;
        if (request.problemIds() != null) {
            problems = orderedProblems(worksheet.getWorkspaceId(), request.problemIds());
            itemRepository.deleteByWorksheetId(worksheetId);
            saveItems(worksheetId, problems);
        } else {
            problems = problemsOf(worksheet);
        }
        return toResponse(worksheet, problems);
    }

    @Transactional
    public void delete(Long worksheetId) {
        Worksheet worksheet = getWorksheet(worksheetId);
        itemRepository.deleteByWorksheetId(worksheetId);
        worksheetRepository.delete(worksheet);
    }

    /** PDF 출력(#18)도 이 메서드로 시험지와 접근 권한을 확인한다 */
    public Worksheet getWorksheet(Long worksheetId) {
        Worksheet worksheet = worksheetRepository.findById(worksheetId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.check(worksheet.getWorkspaceId());
        return worksheet;
    }

    /** 저장된 순서대로의 문항 (문항이 삭제되었으면 빠진다) */
    public List<Problem> problemsOf(Worksheet worksheet) {
        List<WorksheetItem> items = itemRepository.findByWorksheetIdOrderByOrderNo(worksheet.getId());
        Map<Long, Problem> problems = problemRepository.findAllById(items.stream().map(WorksheetItem::getProblemId).toList())
                .stream().collect(Collectors.toMap(Problem::getId, Function.identity()));
        return items.stream().map(i -> problems.get(i.getProblemId())).filter(Objects::nonNull).toList();
    }

    /** 지문 단위로 묶는다: 지문이 처음 나온 순서 → 그 지문 안에서는 요청 순서. 지문마다 번호 1부터 */
    public static List<Section> sections(List<Problem> problems) {
        Map<Object, List<Problem>> groups = new LinkedHashMap<>();
        for (Problem p : problems) {
            groups.computeIfAbsent(p.getPassageId() != null ? p.getPassageId() : "text:" + p.getPassageText(), k -> new ArrayList<>())
                    .add(p);
        }
        List<Section> sections = new ArrayList<>();
        for (List<Problem> group : groups.values()) {
            Problem first = group.get(0);
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                items.add(new Item(i + 1, ProblemResponse.of(group.get(i))));
            }
            sections.add(new Section(sections.size() + 1, first.getPassageId(), first.getPassageTitle(), first.getPassageText(), items));
        }
        return sections;
    }

    private List<Problem> orderedProblems(Long workspaceId, List<Long> problemIds) {
        Set<Long> seen = new HashSet<>();
        for (Long id : problemIds) {
            if (!seen.add(id)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 문항이 두 번 들어 있습니다: " + id);
            }
        }
        Map<Long, Problem> found = problemRepository.findAllById(problemIds).stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));
        List<Problem> requested = new ArrayList<>();
        for (Long id : problemIds) {
            Problem p = found.get(id);
            if (p == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            if (!p.getWorkspaceId().equals(workspaceId)) {
                // 다른 학원 문항이면 존재도 알리지 않도록 404, 같은 학원의 다른 워크스페이스면 400
                accessChecker.check(p.getWorkspaceId());
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 워크스페이스의 문항이 아닙니다: " + id);
            }
            if (p.getReviewStatus() != ReviewStatus.ACCEPTED) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "채택한 문항만 시험지에 넣을 수 있습니다: " + id);
            }
            requested.add(p);
        }
        return sections(requested).stream()
                .flatMap(s -> s.items().stream().map(item -> found.get(item.problem().id())))
                .toList();
    }

    private void saveItems(Long worksheetId, List<Problem> ordered) {
        List<WorksheetItem> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            items.add(new WorksheetItem(worksheetId, ordered.get(i).getId(), i + 1));
        }
        itemRepository.saveAll(items);
    }

    private WorksheetResponse toResponse(Worksheet w, List<Problem> problems) {
        return new WorksheetResponse(w.getId(), w.getWorkspaceId(), w.getTitle(), w.getHeaderText(), w.isShowLogo(),
                problems.size(), sections(problems), w.getCreatedBy(), userNames.name(w.getCreatedBy()), w.getCreatedAt(),
                w.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
