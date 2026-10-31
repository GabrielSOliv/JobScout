package com.jobscout.feature.search.data

import com.jobscout.domain.Job
import com.jobscout.domain.JobLinks
import com.jobscout.domain.JobPageVerifier
import com.jobscout.domain.JobVerification
import com.jobscout.domain.VerificationState
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Checks published content, not the employer's legitimacy or the safety of applying. */
class PublicJobPageVerifier @Inject constructor(
    private val client: OkHttpClient,
    private val robots: RobotsGuard,
) : JobPageVerifier {
    override suspend fun verify(job: Job): Job = withContext(Dispatchers.IO) {
        val checkedAt = System.currentTimeMillis()
        fun result(state: VerificationState, reason: String) = job.copy(
            verification = JobVerification(state, checkedAt, reason, job.verification.validThrough))
        val url = JobLinks.canonical(job.url)
            ?: return@withContext result(VerificationState.NOT_A_JOB, "O endereço não é um link HTTPS público de vaga aceito pelo app.")
        try {
            withTimeout(45_000) {
                robots.check(url)
                val html = client.publicText(url)
                val full = try { HtmlJobPages.detail(html, job.copy(url = url), checkedAt) }
                catch (_: IllegalArgumentException) {
                    return@withTimeout if (isChallenge(html)) result(VerificationState.RESTRICTED,
                        "O site pediu verificação humana ou restringiu a leitura. Acesse a publicação original para conferir.")
                    else result(VerificationState.NOT_A_JOB,
                        "A página respondeu, mas não foi possível reconhecer uma publicação de vaga. Isso não comprova que a oportunidade encerrou.")
                }
                full.copy(id = job.id, source = job.source, sourceId = job.sourceId, url = job.url,
                    firstSeen = job.firstSeen, discoveryMethod = job.discoveryMethod,
                    discoveryOrigins = job.discoveryOrigins)
            }
        } catch (_: TimeoutCancellationException) {
            result(VerificationState.UNVERIFIED, "A verificação atingiu o tempo limite. Os dados anteriores foram preservados.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PublicFetchException) {
            when (error.code) {
                404, 410 -> result(VerificationState.UNAVAILABLE, "A página original respondeu HTTP ${error.code} nesta verificação.")
                401, 403, 429 -> result(VerificationState.RESTRICTED, "A fonte recusou a leitura automática (HTTP ${error.code}).")
                in 300..399 -> result(VerificationState.RESTRICTED, "A fonte redirecionou a leitura. Abra o link original para conferir o destino.")
                else -> result(VerificationState.UNVERIFIED, "Não foi possível confirmar a publicação: HTTP ${error.code}.")
            }
        } catch (_: IllegalArgumentException) {
            result(VerificationState.RESTRICTED, "As regras de acesso da fonte não permitiram confirmar a leitura automática.")
        } catch (_: IOException) {
            result(VerificationState.UNVERIFIED, "A conexão não permitiu confirmar a página nesta tentativa. Os dados anteriores foram preservados.")
        }
    }

    private fun isChallenge(html: String): Boolean {
        val doc = Jsoup.parse(html.take(2_000_000))
        if (doc.select("#challenge-form,.anomaly-modal,.g-recaptcha,[data-sitekey],#cf-challenge-running,#challenge-running").isNotEmpty()) return true
        val heading = (doc.title() + " " + doc.select("h1,h2").text()).lowercase()
        return listOf("verify you are human", "verifique que você é humano", "access denied", "just a moment", "attention required", "captcha")
            .any(heading::contains)
    }
}
