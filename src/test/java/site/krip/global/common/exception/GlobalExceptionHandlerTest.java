package site.krip.global.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} DB 무결성 위반 분기 단위 테스트 —
 * UNIQUE 위반(SQLState 23505)·DuplicateKeyException 만 409, 그 외(NOT NULL 등)는 500.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DuplicateKeyException → 409")
    void duplicateKeyReturns409() {
        assertThat(handler.handleDataIntegrity(new DuplicateKeyException("dup")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    static Stream<Arguments> integrityCases() {
        return Stream.of(
                Arguments.of("23505", HttpStatus.CONFLICT),               // UNIQUE 위반 → 409
                Arguments.of("23502", HttpStatus.INTERNAL_SERVER_ERROR),  // NOT NULL 위반 → 500 (결함 은폐 방지)
                Arguments.of(null, HttpStatus.INTERNAL_SERVER_ERROR));    // SQL 원인 없음 → 500
    }

    @ParameterizedTest(name = "SQLState {0} -> {1}")
    @MethodSource("integrityCases")
    @DisplayName("무결성 위반은 SQLState 에 따라 409(UNIQUE) 또는 500 으로 매핑된다")
    void dataIntegrityMapsBySqlState(String sqlState, HttpStatus expected) {
        DataIntegrityViolationException e = sqlState == null
                ? new DataIntegrityViolationException("x")
                : new DataIntegrityViolationException("constraint", new SQLException("violation", sqlState));
        assertThat(handler.handleDataIntegrity(e).getStatusCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("낙관적 락 충돌 → 409")
    void optimisticLockReturns409() {
        OptimisticLockingFailureException e =
                new ObjectOptimisticLockingFailureException(Object.class, "id");
        assertThat(handler.handleOptimisticLock(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
