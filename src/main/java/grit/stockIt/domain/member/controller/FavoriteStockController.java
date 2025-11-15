package grit.stockIt.domain.member.controller;

import grit.stockIt.domain.member.dto.CreateFavoriteRequest;
import grit.stockIt.domain.member.dto.FavoriteStockDto;
import grit.stockIt.domain.member.service.FavoriteStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/members/me/favorites")
@RequiredArgsConstructor
@Tag(name = "Member Favorites", description = "관심종목 관리 API")
public class FavoriteStockController {

    private final FavoriteStockService favoriteStockService;

    @Operation(summary = "관심종목 추가", description = "종목을 관심목록에 추가합니다. 이미 등록되어 있으면 기존 엔티티를 반환합니다.")
    @PostMapping
    public ResponseEntity<FavoriteStockDto> addFavorite(
            @Valid @RequestBody CreateFavoriteRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = userDetails.getUsername();
        boolean existed = favoriteStockService.isFavorited(email, request.getStockCode());
        FavoriteStockDto dto = favoriteStockService.addFavorite(email, request.getStockCode());
        if (existed) {
            return ResponseEntity.ok(dto);
        }
        // 생성된 경우 201 및 Location 헤더
        URI location = URI.create(String.format("/api/members/me/favorites/%s", dto.getStockCode()));
        return ResponseEntity.created(location).body(dto);
    }

    @Operation(summary = "관심종목 삭제", description = "관심종목을 목록에서 제거합니다.")
    @DeleteMapping("/{stockCode}")
    public ResponseEntity<Void> removeFavorite(
            @PathVariable String stockCode,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        favoriteStockService.removeFavorite(userDetails.getUsername(), stockCode);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 관심종목 목록 조회", description = "인증된 사용자의 관심종목 목록을 반환합니다.")
    @GetMapping
    public ResponseEntity<List<FavoriteStockDto>> listFavorites(
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<FavoriteStockDto> list = favoriteStockService.listFavorites(userDetails.getUsername());
        return ResponseEntity.ok(list);
    }
}
