package site.krip.domain.tour.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.tour.document.Place;
import site.krip.domain.tour.entity.FavoritePlace;
import site.krip.domain.tour.repository.FavoritePlaceRepository;
import site.krip.domain.tour.repository.PlaceRepository;
import site.krip.global.common.exception.ApiException;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 즐겨찾기 추가 단위 테스트 — 동시 추가 race(UNIQUE 충돌)를 500 이 아닌 400 으로 매핑하는지 검증.
 * race 는 순차 호출로 재현 불가하므로 saveAndFlush 의 {@link DataIntegrityViolationException} 을 mock 주입.
 */
@DisplayName("즐겨찾기 서비스 — 동시 추가 UNIQUE 충돌 400·중복/미존재 차단")
class FavoritePlaceServiceTest {

    private static final String USER = "u1";
    private static final String PLACE = "p1";

    private final FavoritePlaceRepository favRepo = mock(FavoritePlaceRepository.class);
    private final PlaceRepository placeRepo = mock(PlaceRepository.class);
    private final TransactionTemplate txTemplate = mock(TransactionTemplate.class);
    private final FavoritePlaceService service =
            new FavoritePlaceService(favRepo, placeRepo, txTemplate);

    @BeforeEach
    void runTxInline() {
        // executeWithoutResult 가 콜백을 즉시 실행하도록 — 콜백이 던지는 예외도 그대로 전파한다.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    private void placeExists() {
        when(placeRepo.findByPlaceIds(List.of(PLACE))).thenReturn(List.of(mock(Place.class)));
    }

    /** 던져진 예외가 ApiException 이고 지정 status 인지 검증. */
    private static void assertApiStatus(Throwable e, int status) {
        assertThat(((ApiException) e).getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("동시 추가 race: saveAndFlush UNIQUE 충돌 → 500 이 아니라 400")
    void concurrentInsertMapsTo400() {
        // given
        placeExists();
        when(favRepo.existsByUserIdAndPlaceId(USER, PLACE)).thenReturn(false);
        when(favRepo.saveAndFlush(any(FavoritePlace.class)))
                .thenThrow(new DataIntegrityViolationException("uq_user_favorite_place"));

        // when & then
        assertThatThrownBy(() -> service.addFavorite(USER, PLACE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertApiStatus(e, 400));
    }

    @Test
    @DisplayName("순차 중복: existsBy=true → 400, INSERT 시도 안 함")
    void sequentialDuplicateMapsTo400() {
        // given
        placeExists();
        when(favRepo.existsByUserIdAndPlaceId(USER, PLACE)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> service.addFavorite(USER, PLACE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertApiStatus(e, 400));
        verify(favRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("존재하지 않는 장소 → 400, 트랜잭션 진입 안 함")
    void missingPlaceMapsTo400() {
        // given
        when(placeRepo.findByPlaceIds(List.of(PLACE))).thenReturn(List.of());

        // when & then
        assertThatThrownBy(() -> service.addFavorite(USER, PLACE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertApiStatus(e, 400));
        verify(favRepo, never()).existsByUserIdAndPlaceId(any(), any());
    }

    @Test
    @DisplayName("정상: 미존재 → 저장 성공(예외 없음)")
    void happyPathSaves() {
        // given
        placeExists();
        when(favRepo.existsByUserIdAndPlaceId(USER, PLACE)).thenReturn(false);
        when(favRepo.saveAndFlush(any(FavoritePlace.class))).thenReturn(mock(FavoritePlace.class));

        // when
        service.addFavorite(USER, PLACE);

        // then
        verify(favRepo).saveAndFlush(any(FavoritePlace.class));
    }
}
