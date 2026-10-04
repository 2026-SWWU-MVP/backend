package com.smwu.backend.workspace.repository;

import com.smwu.backend.workspace.domain.School;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SchoolRepository extends JpaRepository<School, Long> {

    /** 이름·별칭 부분 일치. key는 School.normalize 한 값 */
    @Query("select s from School s where s.searchKey like concat('%', :key, '%') order by length(s.name), s.name")
    List<School> search(@Param("key") String key, Pageable pageable);
}
