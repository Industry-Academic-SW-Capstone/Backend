package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.StockSearchDto;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockSearchService {

    private final StockRepository stockRepository;

    public List<StockSearchDto> searchByName(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedQuery = normalize(query);

        List<Stock> stocks = stockRepository.findAll();

        // 계산 및 필터링
        List<StockSearchDto> matches = new ArrayList<>();
        for (Stock s : stocks) {
            String name = s.getName();
            String normalizedName = normalize(name);
            double sim;
            // n-gram 기반 Jaccard (2-gram). 짧은 문자열은 문자 기반으로 폴백
            if (normalizedQuery.length() < 2 || normalizedName.length() < 2) {
                sim = jaccardSimilarity(normalizedQuery, normalizedName);
            } else {
                sim = jaccardByNGram(normalizedQuery, normalizedName, 2);
            }
            if (sim > 0d) {
                matches.add(new StockSearchDto(s.getCode(), s.getName(), sim));
            }
        }

        // 유사도 내림차순 정렬
        return matches.stream()
                .sorted(Comparator.comparingDouble(StockSearchDto::similarity).reversed())
                .collect(Collectors.toList());
    }

    private static String normalize(String s) {
        if (s == null) return "";
        // 소문자화, 특수문자 제거(한글,영문,숫자,공백만 허용), 공백 제거(ngram 생성용)
        String replaced = s.replaceAll("[^가-힣0-9a-zA-Z\\s]", "");
        // 회사명 검색에서는 공백을 제거하여 'LG 화학'과 'LG화학'을 동일하게 처리
        return replaced.replaceAll("\\s+", "").toLowerCase().trim();
    }

    // 문자 기반 멀티셋 Jaccard (교집합/합집합)
    private static double jaccardSimilarity(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";
        if (s1.isEmpty() && s2.isEmpty()) return 0d;

        Map<Character, Integer> m1 = new HashMap<>();
        Map<Character, Integer> m2 = new HashMap<>();

        for (char c : s1.toCharArray()) {
            m1.put(c, m1.getOrDefault(c, 0) + 1);
        }
        for (char c : s2.toCharArray()) {
            m2.put(c, m2.getOrDefault(c, 0) + 1);
        }

        Set<Character> union = new HashSet<>();
        union.addAll(m1.keySet());
        union.addAll(m2.keySet());

        double inter = 0d;
        double uni = 0d;

        for (Character c : union) {
            int v1 = m1.getOrDefault(c, 0);
            int v2 = m2.getOrDefault(c, 0);
            inter += Math.min(v1, v2);
            uni += Math.max(v1, v2);
        }

        if (uni == 0d) return 0d;
        return inter / uni;
    }

    // n-gram 기반 Jaccard (set 기반)
    private static double jaccardByNGram(String s1, String s2, int n) {
        Set<String> a = toNGrams(s1, n);
        Set<String> b = toNGrams(s2, n);
        if (a.isEmpty() && b.isEmpty()) return 0d;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uni = new HashSet<>(a);
        uni.addAll(b);
        if (uni.size() == 0) return 0d;
        return (double) inter.size() / (double) uni.size();
    }

    private static Set<String> toNGrams(String s, int n) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        int len = s.length();
        if (len == 0) return out;
        if (len < n) {
            out.add(s);
            return out;
        }
        for (int i = 0; i + n <= len; i++) {
            out.add(s.substring(i, i + n));
        }
        return out;
    }
}
