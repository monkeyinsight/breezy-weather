package wangdaye.com.geometricweather.weather.services;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import wangdaye.com.geometricweather.BuildConfig;
import wangdaye.com.geometricweather.common.basic.models.Location;
import wangdaye.com.geometricweather.common.rxjava.BaseObserver;
import wangdaye.com.geometricweather.common.rxjava.ObserverContainer;
import wangdaye.com.geometricweather.common.rxjava.SchedulerTransformer;
import wangdaye.com.geometricweather.common.utils.DisplayUtils;
import wangdaye.com.geometricweather.settings.SettingsManager;
import wangdaye.com.geometricweather.weather.apis.AtmoAuraIqaApi;
import wangdaye.com.geometricweather.weather.apis.MfWeatherApi;
import wangdaye.com.geometricweather.weather.converters.MfResultConverterKt;
import wangdaye.com.geometricweather.weather.json.atmoaura.AtmoAuraPointResult;
import wangdaye.com.geometricweather.weather.json.mf.MfCurrentResult;
import wangdaye.com.geometricweather.weather.json.mf.MfEphemerisResult;
import wangdaye.com.geometricweather.weather.json.mf.MfForecastResult;
import wangdaye.com.geometricweather.weather.json.mf.MfLocationResult;
import wangdaye.com.geometricweather.weather.json.mf.MfRainResult;
import wangdaye.com.geometricweather.weather.json.mf.MfWarningsResult;

/**
 * Mf weather service.
 */

public class MfWeatherService extends WeatherService {

    private final MfWeatherApi mMfApi;
    private final AtmoAuraIqaApi mAtmoAuraApi;
    private final CompositeDisposable mCompositeDisposable;

    @Inject
    public MfWeatherService(MfWeatherApi mfApi, AtmoAuraIqaApi atmoApi,
                            CompositeDisposable disposable) {
        mMfApi = mfApi;
        mAtmoAuraApi = atmoApi;
        mCompositeDisposable = disposable;
    }

    @Override
    public void requestWeather(Context context, Location location, @NonNull RequestWeatherCallback callback) {
        String languageCode = SettingsManager.getInstance(context).getLanguage().getCode();
        String token = this.getToken(context);

        Observable<MfCurrentResult> current = mMfApi.getCurrent(
            getUserAgent(), location.getLatitude(), location.getLongitude(), languageCode, "iso", token);

        Observable<MfForecastResult> forecast = mMfApi.getForecast(
            getUserAgent(), location.getLatitude(), location.getLongitude(),"iso", token);

        Observable<MfEphemerisResult> ephemeris = mMfApi.getEphemeris(
            getUserAgent(), location.getLatitude(), location.getLongitude(), "en", "iso", token);
        // English required to convert moon phase

        Observable<MfRainResult> rain = mMfApi.getRain(
            getUserAgent(), location.getLatitude(), location.getLongitude(), languageCode, "iso", token);

        Observable<MfWarningsResult> warnings;
        if (!TextUtils.isEmpty(location.getProvince())) {
            warnings = mMfApi.getWarnings(
                    getUserAgent(), location.getProvince(), "iso", token
            ).onErrorResumeNext(error ->
                    Observable.create(emitter -> emitter.onNext(new MfWarningsResult(null, null, null, null, null, null, null, null)))
            );
        } else {
            warnings = Observable.create(emitter -> emitter.onNext(new MfWarningsResult(null, null, null, null, null, null, null, null)));
        }

        Observable<AtmoAuraPointResult> aqiAtmoAura;
        if (location.getProvince().equals("Auvergne-Rhône-Alpes") || location.getProvince().equals("01")
                || location.getProvince().equals("03") || location.getProvince().equals("07")
                || location.getProvince().equals("15") || location.getProvince().equals("26")
                || location.getProvince().equals("38") || location.getProvince().equals("42")
                || location.getProvince().equals("43") || location.getProvince().equals("63")
                || location.getProvince().equals("69") || location.getProvince().equals("73")
                || location.getProvince().equals("74")) {
            Calendar c = DisplayUtils.toCalendarWithTimeZone(new Date(), location.getTimeZone());
            c.add(Calendar.DATE, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            aqiAtmoAura = mAtmoAuraApi.getPointDetails(
                    SettingsManager.getInstance(context).getProviderIqaAtmoAuraKey(),
                    location.getLongitude(),
                    location.getLatitude(),
                    // Tomorrow because it gives access to D-1 and D+1
                    DisplayUtils.getFormattedDate(c.getTime(), location.getTimeZone(), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            ).onErrorResumeNext(error ->
                    Observable.create(emitter -> emitter.onNext(new AtmoAuraPointResult(null)))
            );
        } else {
            aqiAtmoAura = Observable.create(emitter -> emitter.onNext(new AtmoAuraPointResult(null)));
        }

        Observable.zip(current, forecast, ephemeris, rain, warnings, aqiAtmoAura,
                (mfCurrentResult, mfForecastResult, mfEphemerisResult, mfRainResult, mfWarningResults, aqiAtmoAuraResult) -> MfResultConverterKt.convert(
                        context,
                        location,
                        mfCurrentResult,
                        mfForecastResult,
                        mfEphemerisResult,
                        mfRainResult,
                        mfWarningResults,
                        aqiAtmoAuraResult
                )
        ).compose(SchedulerTransformer.create())
                .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<WeatherResultWrapper>() {
                    @Override
                    public void onSucceed(WeatherResultWrapper wrapper) {
                        if (wrapper.result != null) {
                            callback.requestWeatherSuccess(
                                    Location.copy(location, wrapper.result)
                            );
                        } else {
                            onFailed();
                        }
                    }

                    @Override
                    public void onFailed() {
                        callback.requestWeatherFailed(location, this.isApiLimitReached(), this.isApiUnauthorized());
                    }
                }));
    }

    @Override
    @NonNull
    public List<Location> requestLocation(Context context, String query) {
        List<MfLocationResult> resultList = null;
        try {
            resultList = mMfApi.callWeatherLocation(getUserAgent(), query, 48.86d, 2.34d, this.getToken(context)).execute().body();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return MfResultConverterKt.convert(resultList);
    }

    @Override
    public void requestLocation(Context context, Location location,
                                @NonNull RequestLocationCallback callback) {
        mMfApi.getForecast(
            getUserAgent(),
            location.getLatitude(),
            location.getLongitude(),
            "iso",
            this.getToken(context)
        ).compose(SchedulerTransformer.create())
            .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<MfForecastResult>() {
                @Override
                public void onSucceed(MfForecastResult mfForecastResult) {
                    if (mfForecastResult != null) {
                        List<Location> locationList = new ArrayList<>();
                        Location location = MfResultConverterKt.convert(null, mfForecastResult);
                        if (location != null) {
                            locationList.add(location);
                            callback.requestLocationSuccess(
                                    location.getLatitude() + "," + location.getLongitude(),
                                    locationList
                            );
                        } else {
                            onFailed();
                        }
                    } else {
                        onFailed();
                    }
                }

                @Override
                public void onFailed() {
                    callback.requestLocationFailed(
                            location.getLatitude() + "," + location.getLongitude()
                    );
                }
            }));
    }

    public void requestLocation(Context context, String query,
                                @NonNull RequestLocationCallback callback) {
        mMfApi.getWeatherLocation(getUserAgent(), query, 48.86d, 2.34d, this.getToken(context))
                .compose(SchedulerTransformer.create())
                .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<List<MfLocationResult>>() {
                    @Override
                    public void onSucceed(List<MfLocationResult> mfLocationResults) {
                        if (mfLocationResults != null && mfLocationResults.size() != 0) {
                            List<Location> locationList = MfResultConverterKt.convert(mfLocationResults);
                            callback.requestLocationSuccess(query, locationList);
                        } else {
                            callback.requestLocationFailed(query);
                        }
                    }

                    @Override
                    public void onFailed() {
                        callback.requestLocationFailed(query);
                    }
                }));
    }

    @Override
    public void cancel() {
        mCompositeDisposable.clear();
    }

    protected String getToken(Context context) {
        if (!SettingsManager.getInstance(context).getProviderMfWsftKey().equals(BuildConfig.MF_WSFT_KEY)) {
            return SettingsManager.getInstance(context).getProviderMfWsftKey();
        } else {
            try {
                JwtBuilder jwtsBuilder = Jwts.builder();
                jwtsBuilder.setHeaderParam(Header.TYPE, Header.JWT_TYPE);

                HashMap<String, String> claims = new HashMap<>();
                claims.put("class", "mobile");
                claims.put(Claims.ISSUED_AT, String.valueOf(new Date().getTime() / 1000));
                claims.put(Claims.ID, UUID.randomUUID().toString());
                jwtsBuilder.setClaims(claims);

                byte[] keyBytes = BuildConfig.MF_WSFT_JWT_KEY.getBytes(StandardCharsets.UTF_8);
                jwtsBuilder.signWith(Keys.hmacShaKeyFor(keyBytes), SignatureAlgorithm.HS256);
                return jwtsBuilder.compact();
            } catch (Exception ignored) {
                return BuildConfig.MF_WSFT_KEY;
            }
        }
    }

    protected String getUserAgent() {
        return "okhttp/4.9.2";
    }
}