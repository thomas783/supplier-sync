package mocksupplier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Mock 공급사 서버 (별도 모듈의 별도 애플리케이션).
 *
 * 본 애플리케이션과 다른 포트(기본 9090)로 띄운다 — 같은 포트에 두면 자기 자신을 HTTP 로 호출하게 되어
 * 스레드가 묶이며 연동 문제로 오해할 실패가 생긴다. 별도 Gradle 모듈이라 본 앱 아티팩트에 Mock 코드가
 * 실리지 않고, 의존성도 웹 스타터뿐이라 DB 없이 단독으로 뜬다.
 *
 * 실행: `./gradlew :mock-supplier:bootRun`
 */
@SpringBootApplication
class MockSupplierApplication

fun main(args: Array<String>) {
    runApplication<MockSupplierApplication>(*args)
}
