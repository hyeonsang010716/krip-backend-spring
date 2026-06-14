package site.krip.domain.ai.tour;

import java.util.Map;

/**
 * 권역(cluster) → 대표 좌표 매핑. 라우터 검증과 검색점 계산이 동일 키를 공유한다.
 *
 * <p>FastAPI {@code CLUSTER_COORDINATES} 와 1:1 동기화 — 영문 표준명 키, 값은 {@code [lat, lng]}.
 */
public final class TourClusters {

    private TourClusters() {
    }

    private static final Map<String, double[]> COORDINATES = Map.ofEntries(
            Map.entry("Myeongdong / Euljiro", new double[]{37.565, 126.987}),
            Map.entry("Gangnam Station", new double[]{37.500, 127.032}),
            Map.entry("Hongdae / Hapjeong", new double[]{37.555, 126.923}),
            Map.entry("Itaewon", new double[]{37.535, 126.998}),
            Map.entry("Jamsil", new double[]{37.515, 127.083}),
            Map.entry("Konkuk Univ. Station (Kondae)", new double[]{37.543, 127.070}),
            Map.entry("Sinchon / Yonsei Univ.", new double[]{37.558, 126.940}),
            Map.entry("Jongno / Insadong", new double[]{37.575, 126.988}),
            Map.entry("Yeouido", new double[]{37.525, 126.928}),
            Map.entry("Seongsu-dong", new double[]{37.547, 127.060}),
            Map.entry("Mangwon / Yeonnam-dong", new double[]{37.563, 126.912}),
            Map.entry("Euljiro 3-ga / Chungmuro", new double[]{37.566, 126.995}),
            Map.entry("Apgujeong / Cheongdam", new double[]{37.527, 127.047}),
            Map.entry("Garosu-gil (Sinsa)", new double[]{37.521, 127.025}),
            Map.entry("Bukchon / Samcheong-dong", new double[]{37.582, 126.984}),
            Map.entry("Gwangjang Market / Dongdaemun", new double[]{37.572, 127.004}),
            Map.entry("Yongsan / Haebangchon (HBC)", new double[]{37.544, 126.987}),
            Map.entry("Hannam-dong", new double[]{37.536, 127.004}),
            Map.entry("Mullae-dong", new double[]{37.516, 126.900}),
            Map.entry("Songridan-gil (Songpa)", new double[]{37.507, 127.113}),
            Map.entry("Seoul Forest / Ttukseom", new double[]{37.547, 127.048}),
            Map.entry("Mapo / Gongdeok", new double[]{37.546, 126.953}),
            Map.entry("Nakseongdae / Sharosu-gil", new double[]{37.479, 126.955}),
            Map.entry("Hyehwa / Daehangno", new double[]{37.584, 127.004}),
            Map.entry("Hoegi / Kyung Hee Univ.", new double[]{37.590, 127.054}),
            Map.entry("Noryangjin / Dongjak", new double[]{37.513, 126.945}),
            Map.entry("Wangsimni / Sangwangsimni", new double[]{37.563, 127.039}),
            Map.entry("Dosan Park / Hak-dong", new double[]{37.524, 127.035}),
            Map.entry("Samseong / COEX", new double[]{37.512, 127.060}),
            Map.entry("Bangbae / Seorae Village", new double[]{37.482, 126.993}),
            Map.entry("Sangsu-dong", new double[]{37.550, 126.924}),
            Map.entry("Ikseon-dong", new double[]{37.575, 126.991}),
            Map.entry("Banpo Hangang Park", new double[]{37.509, 126.998}),
            Map.entry("N Seoul Tower Area (Namsan)", new double[]{37.552, 126.989}),
            Map.entry("DDP / Dongdaemun", new double[]{37.569, 127.011}),
            Map.entry("Seongbuk-dong", new double[]{37.597, 126.995}),
            Map.entry("Yeonhui-dong", new double[]{37.573, 126.932}),
            Map.entry("Ssangmun / Suyu", new double[]{37.650, 127.024})
    );

    public static boolean contains(String cluster) {
        return COORDINATES.containsKey(cluster);
    }

    /** {@code [lat, lng]} 반환, 없으면 null. */
    public static double[] get(String cluster) {
        return COORDINATES.get(cluster);
    }
}
