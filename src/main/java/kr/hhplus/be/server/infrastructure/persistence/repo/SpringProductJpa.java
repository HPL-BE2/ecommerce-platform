package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringProductJpa extends JpaRepository<ProductEntity, Long> {
    // 기본: id ASC keyset
    List<ProductEntity> findTop100ByIdGreaterThanOrderByIdAsc(Long id);

    // 검색(q: name LIKE) + category는 데모 단순화를 위해 생략(필요 시 조인/컬럼 추가)
    @Query("""
           SELECT p FROM ProductEntity p
           WHERE (:cursor IS NULL OR p.id > :cursor)
             AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')))
           ORDER BY 
             CASE WHEN :sort = 'price' THEN p.price END ASC,
             p.id ASC
           """)
    List<ProductEntity> search(Long cursor, String q, String sort);
}
