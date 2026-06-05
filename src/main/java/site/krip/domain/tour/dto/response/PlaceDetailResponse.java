package site.krip.domain.tour.dto.response;

import site.krip.domain.tour.document.Place;

import java.util.List;

/**
 * 장소 상세 응답.
 *
 * <p>{@code location} 은 GeoJSON 의 [lng,lat] 를 API 친화적 lat/lng 로 변환한다.
 */
public record PlaceDetailResponse(
        String placeId,
        String displayName,
        String category,
        List<String> types,
        String address,
        String shortAddress,
        PlaceLocationResponse location,
        Double rating,
        Integer ratingCount,
        String priceLevel,
        PlacePriceRangeResponse priceRange,
        String editorialSummary,
        String generativeSummary,
        String reviewSummary,
        String phone,
        String phoneInternational,
        String website,
        String googleMapsUrl,
        String googleMapReviewLink,
        List<String> openingHours,
        List<String> services,
        List<String> payment,
        List<String> accessibility,
        List<String> parking,
        List<PlaceReviewResponse> reviews,
        List<String> photos
) {
    public static PlaceDetailResponse from(Place p) {
        Place.Location loc = p.getLocation();
        PlaceLocationResponse location = new PlaceLocationResponse(
                loc != null ? loc.getLat() : 0.0, loc != null ? loc.getLng() : 0.0);

        PlacePriceRangeResponse priceRange = p.getPriceRange() != null
                ? new PlacePriceRangeResponse(p.getPriceRange().getMin(), p.getPriceRange().getMax())
                : null;

        List<PlaceReviewResponse> reviews = p.getReviews().stream()
                .map(r -> new PlaceReviewResponse(
                        r.getAuthor(), r.getRating(), r.getRelativeTime(), r.getText()))
                .toList();

        return new PlaceDetailResponse(
                p.getPlaceId(), p.getDisplayName(), p.getCategory(), p.getTypes(),
                p.getAddress(), p.getShortAddress(), location, p.getRating(), p.getRatingCount(),
                p.getPriceLevel(), priceRange, p.getEditorialSummary(), p.getGenerativeSummary(),
                p.getReviewSummary(), p.getPhone(), p.getPhoneInternational(), p.getWebsite(),
                p.getGoogleMapsUrl(), p.getGoogleMapReviewLink(), p.getOpeningHours(),
                p.getServices(), p.getPayment(), p.getAccessibility(), p.getParking(),
                reviews, p.getPhotos());
    }
}
