package site.krip.global.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;

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

    @Test
    @DisplayName("UNIQUE 위반(SQLState 23505) 원인 체인 → 409")
    void uniqueViolationReturns409() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "constraint", new SQLException("duplicate key", "23505"));
        assertThat(handler.handleDataIntegrity(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("NOT NULL 위반(SQLState 23502) → 500 (서버 결함 은폐 방지)")
    void notNullViolationReturns500() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "not null", new SQLException("null value", "23502"));
        assertThat(handler.handleDataIntegrity(e).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("SQL 원인 없는 무결성 위반 → 500")
    void noSqlCauseReturns500() {
        assertThat(handler.handleDataIntegrity(new DataIntegrityViolationException("x")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("낙관적 락 충돌 → 409")
    void optimisticLockReturns409() {
        OptimisticLockingFailureException e =
                new ObjectOptimisticLockingFailureException(Object.class, "id");
        assertThat(handler.handleOptimisticLock(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
