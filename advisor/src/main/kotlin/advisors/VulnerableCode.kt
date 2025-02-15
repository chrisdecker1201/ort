/*
 * Copyright (C) 2021 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.advisor.advisors

import java.net.URI
import java.time.Instant

import org.apache.logging.log4j.kotlin.Logging

import org.ossreviewtoolkit.advisor.AbstractAdviceProviderFactory
import org.ossreviewtoolkit.advisor.AdviceProvider
import org.ossreviewtoolkit.clients.vulnerablecode.VulnerableCodeService
import org.ossreviewtoolkit.clients.vulnerablecode.VulnerableCodeService.PackagesWrapper
import org.ossreviewtoolkit.model.AdvisorCapability
import org.ossreviewtoolkit.model.AdvisorDetails
import org.ossreviewtoolkit.model.AdvisorResult
import org.ossreviewtoolkit.model.AdvisorSummary
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.Vulnerability
import org.ossreviewtoolkit.model.VulnerabilityReference
import org.ossreviewtoolkit.model.config.AdvisorConfiguration
import org.ossreviewtoolkit.model.config.VulnerableCodeConfiguration
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.utils.common.enumSetOf
import org.ossreviewtoolkit.utils.ort.OkHttpClientHelper

/**
 * The number of elements to request at once in a bulk request. This value was chosen more or less randomly to keep the
 * size of responses reasonably small.
 */
private const val BULK_REQUEST_SIZE = 100

/**
 * An [AdviceProvider] implementation that obtains security vulnerability information from a
 * [VulnerableCode][https://github.com/nexB/vulnerablecode] instance.
 */
class VulnerableCode(name: String, config: VulnerableCodeConfiguration) : AdviceProvider(name) {
    private companion object : Logging

    class Factory : AbstractAdviceProviderFactory<VulnerableCode>("VulnerableCode") {
        override fun create(config: AdvisorConfiguration) = VulnerableCode(type, config.forProvider { vulnerableCode })
    }

    /**
     * The details returned with each [AdvisorResult] produced by this instance. As this is constant, it can be
     * created once beforehand.
     */
    override val details = AdvisorDetails(providerName, enumSetOf(AdvisorCapability.VULNERABILITIES))

    private val service by lazy {
        VulnerableCodeService.create(
            config.serverUrl, config.apiKey, OkHttpClientHelper.buildClient()
        )
    }

    override suspend fun retrievePackageFindings(packages: Set<Package>): Map<Package, List<AdvisorResult>> {
        val startTime = Instant.now()

        return runCatching {
            buildMap {
                packages.chunked(BULK_REQUEST_SIZE).forEach { pkg ->
                    putAll(loadVulnerabilities(pkg, startTime))
                }
            }
        }.getOrElse {
            createFailedResults(startTime, packages, it)
        }
    }

    /**
     * Load vulnerability information for the given [packages] and create a map with results per package using the
     * [startTime].
     */
    private suspend fun loadVulnerabilities(
        packages: List<Package>,
        startTime: Instant
    ): Map<Package, List<AdvisorResult>> {
        val packageMap = packages.filter { it.purl.isNotEmpty() }.associateBy { it.purl }
        val packageVulnerabilities = service.getPackageVulnerabilities(PackagesWrapper(packageMap.keys))
        val issues = mutableListOf<Issue>()

        return packageVulnerabilities.filter { it.unresolvedVulnerabilities.isNotEmpty() }.mapNotNull { pv ->
            packageMap[pv.purl]?.let { pkg ->
                val vulnerabilities = pv.unresolvedVulnerabilities.map { it.toModel(issues) }
                val summary = AdvisorSummary(startTime, Instant.now(), issues)
                pkg to listOf(AdvisorResult(details, summary, vulnerabilities = vulnerabilities))
            }
        }.toMap()
    }

    /**
     * Convert this vulnerability from the VulnerableCode data model to a [Vulnerability]. Populate [issues] if this
     * fails.
     */
    private fun VulnerableCodeService.Vulnerability.toModel(issues: MutableList<Issue>): Vulnerability =
        Vulnerability(id = preferredCommonId(), references = references.flatMap { it.toModel(issues) })

    /**
     * Convert this reference from the VulnerableCode data model to a list of [VulnerabilityReference] objects.
     * In the VulnerableCode model, the reference can be assigned multiple scores in different scoring systems.
     * For each of these scores, a single [VulnerabilityReference] is created. If no score is available, return a
     * list with a single [VulnerabilityReference] with limited data. Populate [issues] in case of a failure,
     * e.g. if the conversion to a URI fails.
     */
    private fun VulnerableCodeService.VulnerabilityReference.toModel(
        issues: MutableList<Issue>
    ): List<VulnerabilityReference> = runCatching {
        val sourceUri = URI(url)
        if (scores.isEmpty()) return listOf(VulnerabilityReference(sourceUri, null, null))
        return scores.map {
            // VulnerableCode returns MODERATE instead of MEDIUM in case of cvssv3.1_qr, see:
            // https://github.com/nexB/vulnerablecode/issues/1186
            val severity = if (it.scoringSystem == "cvssv3.1_qr" && it.value == "MODERATE") "MEDIUM" else it.value

            VulnerabilityReference(sourceUri, it.scoringSystem, severity)
        }
    }.onFailure {
        issues += createAndLogIssue(providerName, "Failed to map $this to ORT model due to $it.", Severity.HINT)
    }.getOrElse { emptyList() }

    /**
     * Return a meaningful identifier for this vulnerability that can be used in reports. Obtain this identifier from
     * the defined aliases if there are any. The data model of VulnerableCode supports multiple aliases while ORT's
     * [Vulnerability] has just one identifier. To resolve this discrepancy, prefer CVEs over other identifiers. If
     * there are no aliases referencing CVEs, use an arbitrary alias, assuming that every alias is preferable over
     * the provider-specific ID of VulnerableCode. Only if no aliases are defined, use the latter as fallback. Note
     * that it should still be possible via the references to find mentions of aliases that have been dropped.
     */
    private fun VulnerableCodeService.Vulnerability.preferredCommonId(): String {
        if (aliases.isEmpty()) return vulnerabilityId

        return aliases.firstOrNull { it.startsWith("cve", ignoreCase = true) } ?: aliases.first()
    }
}
