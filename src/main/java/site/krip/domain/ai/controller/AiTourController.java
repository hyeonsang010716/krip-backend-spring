package site.krip.domain.ai.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.ai.dto.request.TourRecommendRequest;
import site.krip.domain.ai.dto.response.TourRecommendResponse;
import site.krip.domain.ai.service.AiTourService;

/** 여행 추천 API — 후보 조회/가공은 Spring, LLM 일정 생성은 FastAPI 위임. */
@RestController
@RequestMapping("/api/tour")
public class AiTourController {

    private final AiTourService tourService;

    public AiTourController(AiTourService tourService) {
        this.tourService = tourService;
    }

    @PostMapping("/recommend")
    @ResponseStatus(HttpStatus.OK)
    public TourRecommendResponse recommend(@Valid @RequestBody TourRecommendRequest body) {
        return tourService.recommend(body);
    }
}
