package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.dto.FavoriteStockDto;
import grit.stockIt.domain.member.entity.FavoriteStock;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.FavoriteStockRepository;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.domain.stock.dto.StockDetailDto;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteStockService {

    private final FavoriteStockRepository favoriteStockRepository;
    private final MemberRepository memberRepository;
    private final StockRepository stockRepository;
        private final StockDetailService stockDetailService;

    @Transactional
    public FavoriteStockDto addFavorite(String memberEmail, String stockCode) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberEmail));

        Stock stock = stockRepository.findById(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

                Optional<FavoriteStock> exist = favoriteStockRepository.findByMemberAndStock(member, stock);
                if (exist.isPresent()) {
                        FavoriteStock f = exist.get();
                        // try to enrich with market data
                        return buildFavoriteDtoWithMarketData(f.getFavoriteStockId(), f.getStock().getCode(), f.getStock().getName(), f.getCreatedAt());
                }

        FavoriteStock saved = favoriteStockRepository.save(FavoriteStock.of(member, stock));
        return buildFavoriteDtoWithMarketData(saved.getFavoriteStockId(), saved.getStock().getCode(), saved.getStock().getName(), saved.getCreatedAt());
    }

    @Transactional
    public boolean isFavorited(String memberEmail, String stockCode) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberEmail));

        Stock stock = stockRepository.findById(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        return favoriteStockRepository.findByMemberAndStock(member, stock).isPresent();
    }

    @Transactional
    public void removeFavorite(String memberEmail, String stockCode) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberEmail));

        Stock stock = stockRepository.findById(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        favoriteStockRepository.findByMemberAndStock(member, stock)
                .ifPresent(favoriteStockRepository::delete);
    }

    @Transactional
    public List<FavoriteStockDto> listFavorites(String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberEmail));

        List<FavoriteStock> list = favoriteStockRepository.findAllByMember(member);
                return list.stream().map(f -> buildFavoriteDtoWithMarketData(
                                f.getFavoriteStockId(), f.getStock().getCode(), f.getStock().getName(), f.getCreatedAt()
                )).collect(Collectors.toList());
    }

        private FavoriteStockDto buildFavoriteDtoWithMarketData(Long favoriteId, String stockCode, String stockName, java.time.LocalDateTime addedAt) {
                String marketType = null;
                Integer currentPrice = null;
                Double changeRate = null;
                String changeSign = null;
                Integer changeAmount = null;

                try {
                        StockDetailDto detail = stockDetailService.getStockDetail(stockCode).block();
                        if (detail != null) {
                                currentPrice = detail.currentPrice();
                                changeAmount = detail.changeAmount();
                                // changeRate in DTO is String, try parse
                                try {
                                        changeRate = Double.parseDouble(detail.changeRate());
                                } catch (Exception e) {
                                        changeRate = 0.0;
                                }
                                changeSign = detail.changeSign() != null ? detail.changeSign().name() : null;
                        }
                } catch (Exception ex) {
                        // log at debug level; fall back to stock DB values
                }

                // marketType from DB stock entry if available
                try {
                        var stockOpt = stockRepository.findById(stockCode);
                        if (stockOpt.isPresent()) {
                                marketType = stockOpt.get().getMarketType();
                        }
                } catch (Exception e) {
                        // ignore
                }

                return FavoriteStockDto.builder()
                                .favoriteId(favoriteId)
                                .stockCode(stockCode)
                                .stockName(stockName)
                                .addedAt(addedAt)
                                .marketType(marketType)
                                .currentPrice(currentPrice)
                                .changeRate(changeRate)
                                .changeSign(changeSign)
                                .changeAmount(changeAmount)
                                .build();
        }
}
