package site.krip.domain.tour.document;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

/**
 * 서울 관광 장소.
 *
 * <p>Google Places 기반 서울 장소 데이터. {@code location} 은 GeoJSON Point 로 저장되어
 * 근처 장소 검색($geoNear)을 지원한다. 거리순 조회 결과의 {@code distance} 는 문서 필드가 아니라
 * 집계 단계 산출값이라 별도 매핑하지 않는다(레포지토리가 raw Document 에서 추출).
 *
 * <p>Google Places 응답은 다수 필드가 optional 이라 해당 필드는 {@code @Nullable}. 방어적 getter
 * (types/reviews/photos)는 null 을 빈 리스트로 폴백하므로 반환은 non-null 이다.
 */
@Document(collection = "place")
public class Place {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("place_id")
    private String placeId;

    @Field("display_name")
    private String displayName;

    @Indexed
    @Field("category")
    private String category;

    @Field("types")
    private @Nullable List<String> types;

    @Field("address")
    private String address;

    @Field("short_address")
    private String shortAddress;

    // GeoJSON Point — $geoNear 를 위해 2dsphere 인덱스 자동 생성(auto-index-creation=true).
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    @Field("location")
    private @Nullable Location location;

    @Field("rating")
    private @Nullable Double rating;

    @Field("rating_count")
    private @Nullable Integer ratingCount;

    @Field("price_level")
    private @Nullable String priceLevel;

    @Field("price_range")
    private @Nullable PriceRange priceRange;

    @Field("editorial_summary")
    private @Nullable String editorialSummary;

    @Field("generative_summary")
    private @Nullable String generativeSummary;

    @Field("review_summary")
    private @Nullable String reviewSummary;

    @Field("phone")
    private @Nullable String phone;

    @Field("phone_international")
    private @Nullable String phoneInternational;

    @Field("website")
    private @Nullable String website;

    @Field("google_maps_url")
    private @Nullable String googleMapsUrl;

    @Field("google_map_review_link")
    private @Nullable String googleMapReviewLink;

    @Field("opening_hours")
    private @Nullable List<String> openingHours;

    @Field("services")
    private @Nullable List<String> services;

    @Field("payment")
    private @Nullable List<String> payment;

    @Field("accessibility")
    private @Nullable List<String> accessibility;

    @Field("parking")
    private @Nullable List<String> parking;

    @Field("reviews")
    private @Nullable List<Review> reviews;

    @Field("photos")
    private @Nullable List<String> photos;

    protected Place() {
    }

    /** GeoJSON Point — coordinates 는 [경도(lng), 위도(lat)] 순서. */
    public static class Location {
        @Field("type")
        private String type;
        @Field("coordinates")
        private @Nullable List<Double> coordinates;

        public List<Double> getCoordinates() {
            return coordinates != null ? coordinates : List.of(0.0, 0.0);
        }

        /** 위도 (coordinates[1]). */
        public double getLat() {
            List<Double> c = getCoordinates();
            return c.size() > 1 ? c.get(1) : 0.0;
        }

        /** 경도 (coordinates[0]). */
        public double getLng() {
            List<Double> c = getCoordinates();
            return !c.isEmpty() ? c.get(0) : 0.0;
        }
    }

    public static class PriceRange {
        @Field("min")
        private @Nullable String min;
        @Field("max")
        private @Nullable String max;

        public @Nullable String getMin() {
            return min;
        }

        public @Nullable String getMax() {
            return max;
        }
    }

    public static class Review {
        @Field("author")
        private @Nullable String author;
        @Field("rating")
        private @Nullable Integer rating;
        @Field("relative_time")
        private @Nullable String relativeTime;
        @Field("text")
        private @Nullable String text;

        public String getAuthor() {
            return author != null ? author : "";
        }

        public @Nullable Integer getRating() {
            return rating;
        }

        public @Nullable String getRelativeTime() {
            return relativeTime;
        }

        public @Nullable String getText() {
            return text;
        }
    }

    public String getPlaceId() {
        return placeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public List<String> getTypes() {
        return types != null ? types : List.of();
    }

    public String getAddress() {
        return address;
    }

    public String getShortAddress() {
        return shortAddress;
    }

    public @Nullable Location getLocation() {
        return location;
    }

    public @Nullable Double getRating() {
        return rating;
    }

    public @Nullable Integer getRatingCount() {
        return ratingCount;
    }

    public @Nullable String getPriceLevel() {
        return priceLevel;
    }

    public @Nullable PriceRange getPriceRange() {
        return priceRange;
    }

    public @Nullable String getEditorialSummary() {
        return editorialSummary;
    }

    public @Nullable String getGenerativeSummary() {
        return generativeSummary;
    }

    public @Nullable String getReviewSummary() {
        return reviewSummary;
    }

    public @Nullable String getPhone() {
        return phone;
    }

    public @Nullable String getPhoneInternational() {
        return phoneInternational;
    }

    public @Nullable String getWebsite() {
        return website;
    }

    public @Nullable String getGoogleMapsUrl() {
        return googleMapsUrl;
    }

    public @Nullable String getGoogleMapReviewLink() {
        return googleMapReviewLink;
    }

    public @Nullable List<String> getOpeningHours() {
        return openingHours;
    }

    public @Nullable List<String> getServices() {
        return services;
    }

    public @Nullable List<String> getPayment() {
        return payment;
    }

    public @Nullable List<String> getAccessibility() {
        return accessibility;
    }

    public @Nullable List<String> getParking() {
        return parking;
    }

    public List<Review> getReviews() {
        return reviews != null ? reviews : List.of();
    }

    /** null 이면 빈 리스트로 폴백. */
    public List<String> getPhotos() {
        return photos != null ? photos : List.of();
    }
}
