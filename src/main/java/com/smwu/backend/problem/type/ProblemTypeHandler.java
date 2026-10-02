package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;

import java.util.List;

/**
 * 유형별 문제 생성 로직을 한 곳에 모은다. 새 유형은 이 인터페이스 구현체 하나만 추가하면 된다.
 * <ul>
 *   <li>LLM은 {@code D}(유형별 초안) 형식의 내용만 만든다 → prompts/{promptName}.txt, {promptName}.schema.json</li>
 *   <li>발문·[조건]·빈칸 표기·[보기] 순서는 {@link #assemble}에서 코드가 붙인다</li>
 *   <li>{@link #validate}는 코드로 확인할 수 있는 규칙을 검증한다. 실패 항목의 detail은 재생성 프롬프트에 들어간다</li>
 * </ul>
 *
 * @param <D> LLM 응답(초안) 타입
 */
public interface ProblemTypeHandler<D> {

    QuestionType type();

    /** 프롬프트·스키마 파일 이름 (예: generate-summary-blank) */
    String promptName();

    Class<D> draftType();

    /** 발문 (옵션에 따라 결정적으로 생성) */
    String stem(ProblemOptions options);

    /** [조건] 템플릿 (옵션에 따라 결정적으로 생성) */
    List<String> conditions(ProblemOptions options);

    /** 프롬프트에 넣을 이번 문제의 요구 사항 (옵션 설명) */
    String requirements(ProblemOptions options);

    /**
     * LLM 초안으로 문제를 완성한다.
     *
     * @param seed 어구 섞기 등 무작위 요소의 시드 (같은 시드면 같은 결과)
     * @throws IllegalArgumentException 초안이 형식에 맞지 않아 조립할 수 없으면 (메시지는 재생성 프롬프트에 들어감)
     */
    AssembledProblem assemble(D draft, PassageSource passage, ProblemOptions options, long seed);

    /** 코드 규칙 검증. 공통 검증(근거 문장 등)은 호출하는 쪽에서 함께 한다 */
    List<ValidationCheck> validate(AssembledProblem problem, PassageSource passage, ProblemOptions options);
}
