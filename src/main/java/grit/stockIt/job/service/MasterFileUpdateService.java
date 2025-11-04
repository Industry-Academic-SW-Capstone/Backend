package grit.stockIt.job.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.industry.repository.IndustryRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.job.dto.MstRecord;
import grit.stockIt.job.util.MstFileDownloader;
import grit.stockIt.job.util.MstFileParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MasterFileUpdateService {

    private final MstFileDownloader downloader;
    private final MstFileParser parser;
    private final IndustryRepository industryRepository;
    private final StockRepository stockRepository;

    private static final String KOSPI_MST_ZIP_URL = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip";
    private static final String KOSDAQ_MST_ZIP_URL = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip";


    // 매일 새벽 4시에 실행
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional(rollbackFor = Exception.class) // 예외 발생 시 롤백
    public void updateMasterFiles() {
        log.info("종목 마스터 파일 업데이트 작업을 시작합니다.");
        try {
            Set<String> activeKospiStocks = processMarket("KOSPI", KOSPI_MST_ZIP_URL);
            Set<String> activeKosdaqStocks = processMarket("KOSDAQ", KOSDAQ_MST_ZIP_URL);

            // 소프트 삭제 처리
            softDeleteInactiveStocks("KOSPI", activeKospiStocks);
            softDeleteInactiveStocks("KOSDAQ", activeKosdaqStocks);

            log.info("종목 마스터 파일 업데이트 작업을 완료했습니다.");
        } catch (Exception e) {
            log.error("종목 마스터 파일 업데이트 작업 중 예상치 못한 오류 발생", e);
            throw new RuntimeException("마스터 파일 업데이트 작업 실패", e);
        }
    }

    private Set<String> processMarket(String marketName, String zipUrl) {
        try {
            log.info("{} 마스터 파일 처리 시작...", marketName);
            
            // 다운로드 및 압축 해제
            String mstContent = downloader.downloadAndExtractMst(zipUrl).block();
            if (mstContent == null || mstContent.isEmpty()) {
                log.error("{} 마스터 파일 내용을 가져오는데 실패했습니다.", marketName);
                return Collections.emptySet();
            }

            // 파싱
            List<MstRecord> parsedData = parser.parseMstContent(mstContent, marketName);
            if (parsedData.isEmpty()) {
                log.warn("{} 마스터 파일에서 처리할 주식 정보를 찾지 못했습니다.", marketName);
                return Collections.emptySet();
            }

            // 산업 정보 저장/업데이트
            saveUniqueIndustries(parsedData);

            // 주식 정보 저장/업데이트
            Set<String> activeStockCodes = saveOrUpdateStocksUsingIndustryCode(parsedData);

            log.info("{} 마스터 파일 처리 완료. 활성 종목 {}개 확인.", marketName, activeStockCodes.size());
            return activeStockCodes;

        } catch (Exception e) {
            log.error("{} 마스터 파일 처리 중 오류 발생", marketName, e);
            return Collections.emptySet();
        }
    }


    // 업종 코드별 업종명 매핑
    private static final Map<String, String> INDUSTRY_NAME_MAP = createIndustryNameMap();

    private static Map<String, String> createIndustryNameMap() {
        Map<String, String> map = new HashMap<>();
        // KOSPI 업종 코드 (11개)
        map.put("0000", "기타/미분류");
        map.put("0016", "유통/서비스");
        map.put("0017", "에너지/가스");
        map.put("0018", "건설");
        map.put("0019", "운송/물류");
        map.put("0020", "통신");
        map.put("0021", "금융/보험");
        map.put("0026", "서비스");
        map.put("0027", "제조/화학");
        map.put("0028", "부동산");
        map.put("0029", "IT/소프트웨어/게임");
        map.put("0030", "미디어/엔터테인먼트");
        // KOSDAQ 업종 코드 (7개)
        map.put("1006", "IT서비스/소프트웨어");
        map.put("1009", "제조/기계");
        map.put("1010", "건설/엔지니어링");
        map.put("1011", "IT/서비스");
        map.put("1013", "기계/엔지니어링");
        map.put("1014", "금융/투자");
        map.put("1015", "미디어/엔터테인먼트");
        return Collections.unmodifiableMap(map);
    }

    private void saveUniqueIndustries(List<MstRecord> parsedData) {
        Set<String> uniqueIndustryCodes = parsedData.stream()
                .map(MstRecord::industryCode)
                .filter(code -> code != null && !code.isEmpty())
                .collect(Collectors.toSet());

        List<Industry> existingIndustries = industryRepository.findAllById(uniqueIndustryCodes);
        Set<String> existingCodes = existingIndustries.stream()
                .map(Industry::getCode)
                .collect(Collectors.toSet());

        // 신규 산업 저장
        List<Industry> industriesToSave = new ArrayList<>();
        for (String code : uniqueIndustryCodes) {
            if (!existingCodes.contains(code)) {
                String name = INDUSTRY_NAME_MAP.getOrDefault(code, null);
                industriesToSave.add(Industry.builder().code(code).name(name).build());
            }
        }

        // 기존 산업의 이름이 없는 경우 업데이트
        List<Industry> industriesToUpdate = new ArrayList<>();
        for (Industry industry : existingIndustries) {
            if (industry.getName() == null || industry.getName().isEmpty()) {
                String name = INDUSTRY_NAME_MAP.get(industry.getCode());
                if (name != null) {
                    industry.updateName(name);
                    industriesToUpdate.add(industry);
                }
            }
        }

        if (!industriesToSave.isEmpty()) {
            industryRepository.saveAll(industriesToSave);
            log.info("{}개의 신규 산업 코드를 저장했습니다.", industriesToSave.size());
        }

        if (!industriesToUpdate.isEmpty()) {
            industryRepository.saveAll(industriesToUpdate);
            log.info("{}개의 기존 산업 코드의 이름을 업데이트했습니다.", industriesToUpdate.size());
        }
    }


    // Stock 정보를 저장하거나 업데이트. Industry 객체 대신 industryCode 문자열을 직접 사용
    private Set<String> saveOrUpdateStocksUsingIndustryCode(List<MstRecord> parsedData) {
        Set<String> activeStockCodes = new HashSet<>();
        List<Stock> stocksToSaveOrUpdate = new ArrayList<>();
        int updateCount = 0;
        int newCount = 0;

        Set<String> stockCodesInBatch = parsedData.stream().map(MstRecord::stockCode).collect(Collectors.toSet());
        Map<String, Stock> existingStockMap = stockRepository.findAllById(stockCodesInBatch).stream()
                .collect(Collectors.toMap(Stock::getCode, s -> s));


        for (MstRecord data : parsedData) {
            activeStockCodes.add(data.stockCode());

            String industryCode = data.industryCode();
            Stock existingStock = existingStockMap.get(data.stockCode());

            if (existingStock != null) {
                boolean needsUpdate = !Objects.equals(existingStock.getName(), data.stockName()) ||
                        !Objects.equals(existingStock.getMarketType(), data.marketType()) ||
                        !Objects.equals(existingStock.getListingDate(), data.listingDate()) ||
                        !Objects.equals(existingStock.getIndustryCode(), industryCode);

                if (needsUpdate) {
                    try {
                        existingStock.updateName(data.stockName());
                        existingStock.updateMarketType(data.marketType());
                        existingStock.updateListingDate(data.listingDate());
                        existingStock.updateIndustryCode(industryCode);

                        stocksToSaveOrUpdate.add(existingStock);
                        updateCount++;
                    } catch (IllegalArgumentException e) {
                        log.warn("Stock 업데이트 중 유효성 검사 실패 - code: {}, name: {}, market: {}, error: {}",
                                data.stockCode(), data.stockName(), data.marketType(), e.getMessage());
                    }
                }

            } else {
                try {
                    if (data.stockName() == null || data.stockName().trim().isEmpty()) {
                        throw new IllegalArgumentException("종목명은 필수입니다.");
                    }
                    if (data.marketType() == null || data.marketType().trim().isEmpty()) {
                        throw new IllegalArgumentException("시장구분은 필수입니다.");
                    }

                    stocksToSaveOrUpdate.add(Stock.builder()
                            .code(data.stockCode())
                            .name(data.stockName())
                            .marketType(data.marketType())
                            .listingDate(data.listingDate())
                            .industryCode(industryCode)
                            .build());
                    newCount++;
                } catch (IllegalArgumentException e) {
                    log.warn("Stock 생성 중 유효성 검사 실패 - code: {}, name: {}, market: {}, error: {}",
                            data.stockCode(), data.stockName(), data.marketType(), e.getMessage());
                }
            }
        }

        if (!stocksToSaveOrUpdate.isEmpty()) {
            stockRepository.saveAll(stocksToSaveOrUpdate);
            log.info("{}개의 주식 정보를 저장/업데이트했습니다. (신규: {}, 업데이트: {})",
                    stocksToSaveOrUpdate.size(), newCount, updateCount);
        }
        return activeStockCodes;
    }

    private void softDeleteInactiveStocks(String marketName, Set<String> activeStockCodes) {
        log.info("{} 시장의 비활성 종목 소프트 삭제 처리 시작...", marketName);
        List<Stock> currentActiveStocks = stockRepository.findByMarketType(marketName);

        List<Stock> stocksToDelete = currentActiveStocks.stream()
                .filter(stock -> !activeStockCodes.contains(stock.getCode()))
                .collect(Collectors.toList());

        if (!stocksToDelete.isEmpty()) {
            stockRepository.deleteAllInBatch(stocksToDelete);
            log.info("{}개의 비활성 종목을 소프트 삭제 처리했습니다: {}", stocksToDelete.size(), stocksToDelete.stream().map(Stock::getCode).collect(Collectors.joining(",")));
        } else {
            log.info("소프트 삭제할 비활성 종목이 없습니다.");
        }
    }
}