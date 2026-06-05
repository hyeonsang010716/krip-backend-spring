package site.krip.domain.tour.document;

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
    private List<String> types;

    @Field("address")
    private String address;

    @Field("short_address")
    private String shortAddress;

    // GeoJSON Point — $geoNear 를 위해 2dsphere 인덱스 자동 생성(auto-index-creation=true).
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    @Field("location")
    private Location location;

    @Field("rating")
    private Double rating;

    @Field("rating_count")
    private Integer ratingCount;

    @Field("price_level")
    private String priceLevel;

    @Field("price_range")
    private PriceRange priceRange;

    @Field("editorial_summary")
    private String editorialSummary;

    @Field("generative_summary")
    private String generativeSummary;

    @Field("review_summary")
    private String reviewSummary;

    @Field("phone")
    private String phone;

    @Field("phone_international")
    private String phoneInternational;

    @Field("website")
    private String website;

    @Field("google_maps_url")
    private String googleMapsUrl;

    @Field("google_map_review_link")
    private String googleMapReviewLink;

    @Field("opening_hours")
    private List<String> openingHours;

    @Field("services")
    private List<String> services;

    @Field("payment")
    private List<String> payment;

    @Field("accessibility")
    private List<String> accessibility;

    @Field("parking")
    private List<String> parking;

    @Field("reviews")
    private List<Review> reviews;

    @Field("photos")
    private List<String> photos;

    protected Place() {
    }

    /** GeoJSON Point — coordinates 는 [경도(lng), 위도(lat)] 순서. */
    public static class Location {
        @Field("type")
        private String type;
        @Field("coordinates")
        private List<Double> coordinates;

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
        private String min;
        @Field("max")
        private String max;

        public String getMin() {
            return min;
        }

        public String getMax() {
            return max;
        }
    }

    public static class Review {
        @Field("author")
        private String author;
        @Field("rating")
        private Integer rating;
        @Field("relative_time")
        private String relativeTime;
        @Field("text")
        private String text;

        public String getAuthor() {
            return author != null ? author : "";
        }

        public Integer getRating() {
            return rating;
        }

        public String getRelativeTime() {
            return relativeTime;
        }

        public String getText() {
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

    public Location getLocation() {
        return location;
    }

    public Double getRating() {
        return rating;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public String getPriceLevel() {
        return priceLevel;
    }

    public PriceRange getPriceRange() {
        return priceRange;
    }

    public String getEditorialSummary() {
        return editorialSummary;
    }

    public String getGenerativeSummary() {
        return generativeSummary;
    }

    public String getReviewSummary() {
        return reviewSummary;
    }

    public String getPhone() {
        return phone;
    }

    public String getPhoneInternational() {
        return phoneInternational;
    }

    public String getWebsite() {
        return website;
    }

    public String getGoogleMapsUrl() {
        return googleMapsUrl;
    }

    public String getGoogleMapReviewLink() {
        return googleMapReviewLink;
    }

    public List<String> getOpeningHours() {
        return openingHours;
    }

    public List<String> getServices() {
        return services;
    }

    public List<String> getPayment() {
        return payment;
    }

    public List<String> getAccessibility() {
        return accessibility;
    }

    public List<String> getParking() {
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
