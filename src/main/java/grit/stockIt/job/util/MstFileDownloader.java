package grit.stockIt.job.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipInputStream;

@Slf4j
@Component // Bean으로 등록
@RequiredArgsConstructor
public class MstFileDownloader {

    private final WebClient webClient;

    private static final Charset MST_CHARSET = Charset.forName("EUC-KR"); // 확인된 인코딩

    //ZIP 파일을 다운로드하고 압축 해제하여 MST 파일 내용을 반환합니다.
    public Mono<String> downloadAndExtractMst(String zipUrl) {
        log.debug("Downloading MST file from: {}", zipUrl);
        Flux<DataBuffer> dataBufferFlux = webClient.get()
                .uri(zipUrl)
                .retrieve()
                .bodyToFlux(DataBuffer.class);

        return DataBufferUtils.join(dataBufferFlux)
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    log.debug("Download complete, {} bytes received.", bytes.length);
                    return bytes;
                })
                .flatMap(this::extractMstContentFromZip) // Mono<byte[]> -> Mono<String>
                .doOnError(error -> log.error("Failed to download or extract MST file from {}", zipUrl, error));
    }

    private Mono<String> extractMstContentFromZip(byte[] zipBytes) {
        return Mono.fromCallable(() -> { // Blocking I/O 작업을 별도 스레드에서 처리 (권장)
            try (ByteArrayInputStream bis = new ByteArrayInputStream(zipBytes);
                 ZipInputStream zis = new ZipInputStream(bis)) {

                if (zis.getNextEntry() != null) { // zip 파일 안의 첫 번째 파일 (mst 파일)
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    log.debug("Extraction complete.");
                    return bos.toString(MST_CHARSET); // 지정된 인코딩으로 문자열 변환
                } else {
                    throw new RuntimeException("압축 파일 내부에 MST 파일이 없습니다.");
                }
            } catch (Exception e) {
                log.error("Failed to extract MST content from zip.", e);
                throw new RuntimeException("MST 파일 압축 해제 중 오류 발생", e);
            }
        });
    }
}