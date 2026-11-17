package io.github.smyrgeorge.ktkit.ktor.httpclient

import io.github.smyrgeorge.ktkit.api.auth.impl.XRealNamePrincipalExtractor
import io.github.smyrgeorge.ktkit.api.auth.impl.XRealNamePrincipalExtractor.toXRealName
import io.github.smyrgeorge.ktkit.context.Principal
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/** Appends `Authorization: Bearer <token>`, keeping any `Authorization` value already set. */
fun HttpRequestBuilder.bearer(token: String) {
    headers.append(HttpHeaders.Authorization, "Bearer $token")
}

/** Sets the `x-real-name` header that ktkit's [XRealNamePrincipalExtractor] reads back as [principal]. */
fun HttpRequestBuilder.xRealName(principal: Principal) {
    headers[XRealNamePrincipalExtractor.HEADER_NAME] = principal.toXRealName()
}
