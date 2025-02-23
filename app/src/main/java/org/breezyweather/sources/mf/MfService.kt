/**
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.mf

import android.content.Context
import android.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.basic.models.Location
import org.breezyweather.common.basic.wrappers.SecondaryWeatherWrapper
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.ReverseGeocodingSource
import org.breezyweather.common.basic.wrappers.WeatherWrapper
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.common.source.ConfigurableSource
import org.breezyweather.settings.SettingsManager
import org.breezyweather.common.source.MainWeatherSource
import org.breezyweather.common.source.SecondaryWeatherSource
import org.breezyweather.common.source.SecondaryWeatherSourceFeature
import org.breezyweather.settings.SourceConfigStore
import org.breezyweather.sources.mf.json.*
import retrofit2.Retrofit
import java.nio.charset.StandardCharsets
import java.util.*
import javax.inject.Inject

/**
 * Mf weather service.
 */
class MfService @Inject constructor(
    @ApplicationContext context: Context,
    client: Retrofit.Builder
) : HttpSource(), MainWeatherSource, SecondaryWeatherSource,
    ReverseGeocodingSource, ConfigurableSource {

    override val id = "mf"
    override val name = "Météo-France"
    override val privacyPolicyUrl =
        "https://meteofrance.com/application-meteo-france-politique-de-confidentialite"

    override val color = Color.rgb(0, 87, 147)
    override val weatherAttribution = "Météo-France (Etalab)"

    private val mApi by lazy {
        client
            .baseUrl(MF_BASE_URL)
            .build()
            .create(MfApi::class.java)
    }

    override val supportedFeaturesInMain = listOf(
        SecondaryWeatherSourceFeature.FEATURE_MINUTELY,
        SecondaryWeatherSourceFeature.FEATURE_ALERT,
        SecondaryWeatherSourceFeature.FEATURE_NORMALS
    )

    override fun requestWeather(
        context: Context, location: Location,
        ignoreFeatures: List<SecondaryWeatherSourceFeature>
    ): Observable<WeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val languageCode = SettingsManager.getInstance(context).language.code
        val token = getToken()
        val current = mApi.getCurrent(
            userAgent,
            location.latitude.toDouble(),
            location.longitude.toDouble(),
            languageCode,
            "iso",
            token
        )
        val forecast = mApi.getForecast(
            userAgent,
            location.latitude.toDouble(),
            location.longitude.toDouble(),
            "iso",
            token
        )
        val ephemeris = mApi.getEphemeris(
            userAgent,
            location.latitude.toDouble(),
            location.longitude.toDouble(),
            "en", // English required to convert moon phase
            "iso",
            token
        )
        val rain = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)) {
            mApi.getRain(
                userAgent,
                location.latitude.toDouble(),
                location.longitude.toDouble(),
                languageCode,
                "iso",
                token
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(MfRainResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfRainResult())
            }
        }
        val warnings = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)
            && !location.countryCode.isNullOrEmpty()
            && location.countryCode.equals("FR", ignoreCase = true)
            && !location.provinceCode.isNullOrEmpty()
        ) {
            mApi.getWarnings(
                userAgent,
                location.provinceCode,
                "iso",
                token
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(MfWarningsResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfWarningsResult())
            }
        }

        // TODO: Only call once a month, unless it’s current position
        val normals = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_NORMALS)) {
            mApi.getNormals(
                userAgent,
                location.latitude.toDouble(),
                location.longitude.toDouble(),
                token
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(MfNormalsResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfNormalsResult())
            }
        }

        return Observable.zip(
            current,
            forecast,
            ephemeris,
            rain,
            warnings,
            normals
        ) { mfCurrentResult: MfCurrentResult,
            mfForecastResult: MfForecastResult,
            mfEphemerisResult: MfEphemerisResult,
            mfRainResult: MfRainResult,
            mfWarningResults: MfWarningsResult,
            mfNormalsResult: MfNormalsResult
            ->
            convert(
                location,
                mfCurrentResult,
                mfForecastResult,
                mfEphemerisResult,
                mfRainResult,
                mfWarningResults,
                mfNormalsResult
            )
        }
    }

    // SECONDARY WEATHER SOURCE
    override val supportedFeatures = listOf(
        SecondaryWeatherSourceFeature.FEATURE_MINUTELY,
        SecondaryWeatherSourceFeature.FEATURE_ALERT
    )
    override fun isFeatureSupportedForLocation(
        feature: SecondaryWeatherSourceFeature, location: Location
    ): Boolean {
        return isConfigured && (
                feature == SecondaryWeatherSourceFeature.FEATURE_MINUTELY
                        && !location.countryCode.isNullOrEmpty()
                        && location.countryCode.equals("FR", ignoreCase = true)
                ) || (
                feature == SecondaryWeatherSourceFeature.FEATURE_ALERT
                        && !location.countryCode.isNullOrEmpty()
                        && location.countryCode.equals("FR", ignoreCase = true)
                        && !location.provinceCode.isNullOrEmpty()
                ) || feature == SecondaryWeatherSourceFeature.FEATURE_NORMALS
    }
    override val airQualityAttribution = null
    override val pollenAttribution = null
    override val minutelyAttribution = weatherAttribution
    override val alertAttribution = weatherAttribution
    override val normalsAttribution = weatherAttribution

    override fun requestSecondaryWeather(
        context: Context, location: Location,
        requestedFeatures: List<SecondaryWeatherSourceFeature>
    ): Observable<SecondaryWeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val languageCode = SettingsManager.getInstance(context).language.code
        val token = getToken()

        val rain = if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)) {
            mApi.getRain(
                userAgent,
                location.latitude.toDouble(),
                location.longitude.toDouble(),
                languageCode,
                "iso",
                token
            )
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfRainResult())
            }
        }

        val warnings = if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)
            && !location.countryCode.isNullOrEmpty()
            && location.countryCode.equals("FR", ignoreCase = true)
            && !location.provinceCode.isNullOrEmpty()
        ) {
            mApi.getWarnings(
                userAgent,
                location.provinceCode,
                "iso",
                token
            )
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfWarningsResult())
            }
        }

        val normals = if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_NORMALS)) {
            mApi.getNormals(
                userAgent,
                location.latitude.toDouble(),
                location.longitude.toDouble(),
                token
            )
        } else {
            Observable.create { emitter ->
                emitter.onNext(MfNormalsResult())
            }
        }

        return Observable.zip(
            rain,
            warnings,
            normals
        ) { mfRainResult: MfRainResult,
            mfWarningResults: MfWarningsResult,
            mfNormalsResult: MfNormalsResult
            ->
            convertSecondary(
                location,
                if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)) {
                    mfRainResult
                } else null,
                if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)) {
                    mfWarningResults
                } else null,
                if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_NORMALS)) {
                    mfNormalsResult
                } else null
            )
        }
    }

    override fun isUsable(location: Location): Boolean = true

    override fun requestReverseGeocodingLocation(
        context: Context,
        location: Location
    ): Observable<List<Location>> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        return mApi.getForecast(
            userAgent,
            location.latitude.toDouble(),
            location.longitude.toDouble(),
            "iso",
            getToken()
        ).map {
            listOf(convert(location, it))
        }
    }

    // CONFIG
    private val config = SourceConfigStore(context, id)
    private var wsftKey: String
        set(value) {
            config.edit().putString("wsft_key", value).apply()
        }
        get() = config.getString("wsft_key", null) ?: ""

    private fun getWsftKeyOrDefault(): String {
        return wsftKey.ifEmpty { BuildConfig.MF_WSFT_KEY }
    }

    override val isConfigured
        get() = getToken().isNotEmpty()

    override val isRestricted = false

    private fun getToken(): String {
        return if (getWsftKeyOrDefault() != BuildConfig.MF_WSFT_KEY) {
            // If default key was changed, we want to use it
            getWsftKeyOrDefault()
        } else {
            // Otherwise, we try first a JWT key, otherwise fallback on regular API key
            try {
                Jwts.builder().apply {
                    header().add("typ", "JWT")
                    claims().empty().add("class", "mobile")
                    issuedAt(Date())
                    id(UUID.randomUUID().toString())
                    signWith(
                        Keys.hmacShaKeyFor(
                            BuildConfig.MF_WSFT_JWT_KEY.toByteArray(
                                StandardCharsets.UTF_8
                            )
                        ), Jwts.SIG.HS256
                    )
                }.compact()
            } catch (ignored: Exception) {
                BuildConfig.MF_WSFT_KEY
            }
        }
    }

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_provider_mf_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = wsftKey,
                onValueChanged = {
                    wsftKey = it
                }
            )
        )
    }

    companion object {
        private const val MF_BASE_URL = "https://webservice.meteofrance.com/"
        private const val userAgent = "okhttp/4.9.2"
    }
}