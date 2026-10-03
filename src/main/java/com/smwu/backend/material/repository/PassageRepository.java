package com.smwu.backend.material.repository;

import com.smwu.backend.material.domain.Passage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PassageRepository extends JpaRepository<Passage, Long> {

    List<Passage> findByMaterialIdOrderByOrderNoAscIdAsc(Long materialId);

    /** 목록 화면용 지문 수: [materialId, count] */
    @Query("select p.materialId, count(p) from Passage p where p.materialId in :materialIds group by p.materialId")
    List<Object[]> countByMaterial(Collection<Long> materialIds);

    long countByMaterialId(Long materialId);

    @Modifying
    @Query("delete from Passage p where p.materialId = :materialId")
    void deleteByMaterialId(Long materialId);
}
