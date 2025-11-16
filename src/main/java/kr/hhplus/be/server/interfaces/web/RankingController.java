package kr.hhplus.be.server.interfaces.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.hhplus.be.server.application.port.in.GetProductRankingUseCase;
import kr.hhplus.be.server.domain.model.RankingPeriod;
import kr.hhplus.be.server.interfaces.web.dto.ApiEnvelope;
import kr.hhplus.be.server.interfaces.web.dto.RankingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/rankings")
@RequiredArgsConstructor
@Validated
public class RankingController {
    private final GetProductRankingUseCase getProductRankingUseCase;

    @GetMapping("/products")
    public ApiEnvelope<RankingDtos.ProductRankingResponse> topProducts(
            @RequestParam(defaultValue = "REALTIME") RankingPeriod period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate referenceDate,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) Integer limit
    ) {
        var result = getProductRankingUseCase.getTopProducts(
                new GetProductRankingUseCase.Query(period, limit, referenceDate)
        );

        var items = result.items().stream().map(item ->
                new RankingDtos.ProductRankingItem(
                        item.productId(),
                        item.name(),
                        item.thumbnailUrl(),
                        item.unitPrice(),
                        item.score()
                )
        ).toList();

        return new ApiEnvelope<>(new RankingDtos.ProductRankingResponse(items, result.rankingKey()));
    }
}
