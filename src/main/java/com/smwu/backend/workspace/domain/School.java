package com.smwu.backend.workspace.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 공용 학교 목록. 모든 학원이 같은 학교 행을 쓰므로 학교별 DB(3.10)의 기준이 된다.
 * "건대부고"와 "건국대학교사범대학부속고등학교"처럼 부르는 이름이 여러 개라 별칭(aliases)으로도 검색한다.
 */
@Entity
@Table(name = "school", uniqueConstraints = @UniqueConstraint(name = "uk_school_name", columnNames = "normalizedName"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class School extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 시·도 + 시·군·구 (예: 서울 강남구). 같은 이름의 학교를 구분할 때 표시 */
    @Column(length = 100)
    private String region;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> aliases = new ArrayList<>();

    /** 중복 등록 방지용 (공백 제거, 소문자) */
    @Column(nullable = false, length = 100)
    private String normalizedName;

    /** 검색용: 이름과 별칭을 정규화해 | 로 이어 붙인 값 */
    @Column(nullable = false, length = 1000)
    private String searchKey;

    public School(String name, String region, List<String> aliases) {
        this.name = name.strip();
        this.region = region == null || region.isBlank() ? null : region.strip();
        this.aliases = aliases == null ? new ArrayList<>()
                : aliases.stream().filter(a -> a != null && !a.isBlank()).map(String::strip).distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        this.normalizedName = normalize(this.name);
        this.searchKey = Stream.concat(Stream.of(this.name), this.aliases.stream())
                .map(School::normalize).distinct().collect(Collectors.joining("|"));
    }

    /** 이름이나 별칭 중 하나가 정규화 후 같은지 */
    public boolean isCalled(String name) {
        String key = normalize(name);
        return List.of(searchKey.split("\\|")).contains(key);
    }

    public static String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
