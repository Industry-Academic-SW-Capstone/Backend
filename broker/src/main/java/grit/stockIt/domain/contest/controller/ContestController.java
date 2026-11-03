package grit.stockIt.domain.contest.controller;

import grit.stockIt.domain.contest.dto.ContestCreateRequest;
import grit.stockIt.domain.contest.dto.ContestResponse;
import grit.stockIt.domain.contest.dto.ContestUpdateRequest;
import grit.stockIt.domain.contest.service.ContestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
// removed invalid import alias for RequestBody
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import grit.stockIt.domain.account.dto.JoinContestRequest;
import grit.stockIt.domain.account.dto.AccountResponse;

@Slf4j
@RestController
@RequestMapping("/api/contests")
@RequiredArgsConstructor
@Tag(name = "Contest", description = "대회 관리 API")
public class ContestController {

    private final ContestService contestService;
    private final grit.stockIt.domain.account.service.AccountService accountService;
    private final grit.stockIt.domain.member.repository.MemberRepository memberRepository;

    @Operation(
            summary = "대회 생성", 
            description = "새로운 모의투자 대회를 생성합니다",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping
    public ResponseEntity<ContestResponse> createContest(
            @Valid @RequestBody ContestCreateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            log.warn("인증되지 않은 요청 - JWT 토큰이 없습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        ContestResponse response = contestService.createContest(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "대회 목록 조회", description = "모든 대회 목록을 조회합니다 (기본 대회 제외)")
    @GetMapping
    public ResponseEntity<List<ContestResponse>> getAllContests(
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        String userEmail = userDetails != null ? userDetails.getUsername() : null;
        List<ContestResponse> contests = contestService.getAllContests(userEmail);
        return ResponseEntity.ok(contests);
    }

    @Operation(summary = "대회 상세 조회", description = "특정 대회의 상세 정보를 조회합니다")
    @GetMapping("/{contestId}")
    public ResponseEntity<ContestResponse> getContest(
            @Parameter(name = "contestId", description = "대회 ID", required = true)
            @PathVariable("contestId") Long contestId,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        String userEmail = userDetails != null ? userDetails.getUsername() : null;
        ContestResponse response = contestService.getContest(contestId, userEmail);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "대회 수정", 
            description = "대회 정보를 수정합니다 (방장만 가능)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{contestId}")
    public ResponseEntity<ContestResponse> updateContest(
            @Parameter(name = "contestId", description = "대회 ID", required = true)
            @PathVariable("contestId") Long contestId,
            @Valid @RequestBody ContestUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        ContestResponse response = contestService.updateContest(contestId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "대회 삭제", 
            description = "대회를 삭제합니다 (방장만 가능)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @DeleteMapping("/{contestId}")
    public ResponseEntity<Void> deleteContest(
            @Parameter(name = "contestId", description = "대회 ID", required = true)
            @PathVariable("contestId") Long contestId,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        contestService.deleteContest(contestId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "대회 참여",
            description = "인증된 사용자가 특정 대회에 참여하여 계좌를 생성합니다",
            security = @SecurityRequirement(name = "bearerAuth")
    )
        @PostMapping("/{contestId}/join")
        public ResponseEntity<?> joinContest(
            @Parameter(name = "contestId", description = "대회 ID", required = true)
            @PathVariable("contestId") Long contestId,
            @Valid @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "계좌명", required = true)
            @RequestBody JoinContestRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = userDetails.getUsername();
        var memberOpt = memberRepository.findByEmail(email);
        if (memberOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            var account = accountService.createAccountForContest(memberOpt.get(), contestId, request.getAccountName(), request.getPassword());
            var resp = AccountResponse.from(account);
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (grit.stockIt.global.exception.BadRequestException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
        } catch (grit.stockIt.global.exception.ForbiddenException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        } catch (Exception ex) {
            log.error("Error joining contest", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("대회 참여 중 오류가 발생했습니다.");
        }
    }
}
