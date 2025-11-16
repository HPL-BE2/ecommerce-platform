package kr.hhplus.be.server.interfaces.web.dto;

import java.util.List;

public class RankingDtos {

    public record ProductRankingResponse(List<ProductRankingItem> items, String rankingKey) { }

    public record ProductRankingItem(
            Long productId,
            String name,
            String thumbnailUrl,
            int unitPrice,
            long score
    ) { }
}
