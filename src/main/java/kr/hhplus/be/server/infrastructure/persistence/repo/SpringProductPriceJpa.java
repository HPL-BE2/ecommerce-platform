package kr.hhplus.be.server.infrastructure.persistence.repo;

import kr.hhplus.be.server.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringProductPriceJpa extends JpaRepository<ProductEntity, Long> {
    @Query("""
     select p.id as id, p.name as name, CAST(p.price as integer) as price
     from ProductEntity p where p.id in :ids
  """)
    List<ProductView> findActiveWithPrice(@Param("ids") List<Long> ids);

    @Query("""
     select p.id as id, p.name as name, CAST(p.price as integer) as price
     from ProductEntity p where p.id in :ids
  """)
    List<ProductView> findPrice(@Param("ids") List<Long> ids);

    interface ProductView {
        Long getId(); String getName(); Integer getPrice(); String getStatus();
    }
}
