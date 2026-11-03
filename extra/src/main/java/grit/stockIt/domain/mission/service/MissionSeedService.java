package grit.stockIt.domain.mission.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

// 칭호·보상·미션 시드(data.sql)를 DB 에 반영한다. 모두 upsert 라 여러 번 실행해도 같다.
// 예전에는 증권사의 마스터 파일 배치가 함께 실행했지만 부가의 테이블이라 부가가 실행한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionSeedService {

    private static final String SEED = "data.sql";

    private final DataSource dataSource;

    @Transactional
    public void reload() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(SEED));
        populator.setContinueOnError(false);
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
        log.info("미션 시드(data.sql) 반영 완료");
    }
}
