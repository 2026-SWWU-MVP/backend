package com.smwu.backend.worksheet.pdf;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.academy.service.LogoService;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.type.ProblemAnswer;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import com.smwu.backend.worksheet.domain.Worksheet;
import com.smwu.backend.worksheet.dto.WorksheetDtos.Item;
import com.smwu.backend.worksheet.dto.WorksheetDtos.Section;
import com.smwu.backend.worksheet.pdf.SheetView.AnswerKind;
import com.smwu.backend.worksheet.pdf.SheetView.ItemView;
import com.smwu.backend.worksheet.pdf.SheetView.SectionView;
import com.smwu.backend.worksheet.service.WorksheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;

/**
 * 시험지 → 문제지·정답지 PDF (#18). 같은 템플릿에서 정답 표시 여부만 바꿔 별도 PDF로 만든다 (설계서 9).
 * 머리글: 시험지 머리글(없으면 제목) + 학원 로고(없으면 학원명, showLogo=false면 생략)가 매 페이지 반복된다.
 */
@Service
@RequiredArgsConstructor
public class WorksheetPdfService {

    static final String TEMPLATE = "worksheet";

    private final WorksheetService worksheetService;
    private final WorkspaceRepository workspaceRepository;
    private final AcademyRepository academyRepository;
    private final LogoService logoService;
    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    /** @param filename 다운로드 파일 이름 (예: 2과 서술형 대비_정답.pdf) */
    public record PdfFile(String filename, byte[] content) {
    }

    @Transactional(readOnly = true)
    public PdfFile render(Long worksheetId, boolean answerSheet) {
        Worksheet worksheet = worksheetService.getWorksheet(worksheetId);
        List<Section> sections = WorksheetService.sections(worksheetService.problemsOf(worksheet));
        if (sections.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "시험지에 문항이 없습니다.");
        }
        Workspace workspace = workspaceRepository.findById(worksheet.getWorkspaceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Academy academy = academyRepository.findById(workspace.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        SheetView.LogoView logo = worksheet.isShowLogo()
                ? logoService.logoOf(academy.getId()).map(l -> SheetView.LogoView.of(l.dataUri(), l.width(), l.height())).orElse(null)
                : null;
        String academyName = worksheet.isShowLogo() && logo == null ? academy.getName() : null;
        String header = worksheet.getHeaderText() != null ? worksheet.getHeaderText() : worksheet.getTitle();
        SheetView view = new SheetView(RichText.of(answerSheet ? header + " (정답)" : header), logo, academyName, answerSheet,
                sections.stream().map(WorksheetPdfService::toSection).toList());

        Context context = new Context();
        context.setVariable("sheet", view);
        byte[] pdf = pdfRenderer.render(templateEngine.process(TEMPLATE, context));
        return new PdfFile(worksheet.getTitle() + (answerSheet ? "_정답" : "") + ".pdf", pdf);
    }

    private static SectionView toSection(Section s) {
        return new SectionView(s.order(), RichText.of(s.passageTitle()), RichText.of(s.passageText()),
                s.items().stream().map(WorksheetPdfService::toItem).toList());
    }

    static ItemView toItem(Item item) {
        ProblemResponse p = item.problem();
        ProblemAnswer answer = p.answer();
        ProblemOptions options = p.options();
        AnswerKind kind;
        int slots;
        switch (p.type()) {
            case SUMMARY_BLANK -> {
                kind = AnswerKind.BLANKS;
                slots = answer != null && answer.blanks() != null ? answer.blanks().size()
                        : options != null && options.blankCount() != null ? options.blankCount() : 2;
            }
            case GRAMMAR_FIX -> {
                kind = AnswerKind.CORRECTIONS;
                slots = answer != null && answer.corrections() != null ? answer.corrections().size()
                        : options != null && options.errorCount() != null ? options.errorCount() : 1;
            }
            case SENTENCE_ORDER -> {
                kind = AnswerKind.ARROW_LINES;
                slots = 1;
            }
            default -> {
                kind = AnswerKind.ARROW_LINES;
                slots = 2;
            }
        }
        return new ItemView(item.no(), RichText.of(p.stem()), RichText.all(p.conditions()), RichText.of(p.body()),
                RichText.of(String.join(" / ", p.choices())), kind, Math.max(1, slots), RichText.of(p.answerText()),
                RichText.of(p.explanation()));
    }
}
