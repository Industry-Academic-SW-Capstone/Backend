package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.dto.FavoriteStockDto;
import grit.stockIt.domain.member.entity.FavoriteStock;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.FavoriteStockRepository;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
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

    @Transactional
    public FavoriteStockDto addFavorite(String memberEmail, String stockCode) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberEmail));

        Stock stock = stockRepository.findById(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        Optional<FavoriteStock> exist = favoriteStockRepository.findByMemberAndStock(member, stock);
        if (exist.isPresent()) {
            FavoriteStock f = exist.get();
            return FavoriteStockDto.builder()
                    .favoriteId(f.getFavoriteStockId())
                    .stockCode(f.getStock().getCode())
                    .stockName(f.getStock().getName())
                    .addedAt(f.getCreatedAt())
                    .build();
        }

        FavoriteStock saved = favoriteStockRepository.save(FavoriteStock.of(member, stock));
        return FavoriteStockDto.builder()
                .favoriteId(saved.getFavoriteStockId())
                .stockCode(saved.getStock().getCode())
                .stockName(saved.getStock().getName())
                .addedAt(saved.getCreatedAt())
                .build();
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
        return list.stream().map(f -> FavoriteStockDto.builder()
                .favoriteId(f.getFavoriteStockId())
                .stockCode(f.getStock().getCode())
                .stockName(f.getStock().getName())
                .addedAt(f.getCreatedAt())
                .build()).collect(Collectors.toList());
    }
}
