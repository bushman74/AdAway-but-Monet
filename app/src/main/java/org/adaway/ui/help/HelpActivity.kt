package org.adaway.ui.help

import android.graphics.Typeface
import android.text.Html
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch
import org.adaway.R
import org.adaway.ui.compose.ExpressiveAsymmetricShape2
import org.adaway.ui.compose.ExpressiveScaffold
import org.adaway.ui.compose.ExpressiveSection
import org.adaway.ui.compose.ExpressiveTopBar
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

private data class HelpTab(
    @param:StringRes @field:StringRes val titleRes: Int,
    @param:RawRes @field:RawRes val rawRes: Int
)

private val helpTabs = listOf(
    HelpTab(R.string.help_tab_faq, R.raw.help_faq),
    HelpTab(R.string.help_tab_problems, R.raw.help_problems),
    HelpTab(R.string.help_tab_s_on_s_off, R.raw.help_s_on_s_off)
)

@Composable
internal fun HelpRoute(onNavigateBack: () -> Unit) {
    HelpScreen(onNavigateBack = onNavigateBack)
}

/**
 * The help topics, as tabs that can also be swiped between.
 *
 * They used to sit behind a selector that opened a sheet listing them, so every change of topic
 * took two taps and the topics themselves could not be seen.
 */
@Composable
private fun HelpScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val tabContents = remember {
        helpTabs.map { tab ->
            Html.fromHtml(readRawResource(context, tab.rawRes), Html.FROM_HTML_MODE_LEGACY)
        }
    }
    val pagerState = rememberPagerState(pageCount = { helpTabs.size })
    val coroutineScope = rememberCoroutineScope()

    ExpressiveScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ExpressiveTopBar(
                title = stringResource(R.string.menu_help),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                helpTabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(text = stringResource(tab.titleRes)) }
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                ExpressiveSection(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                    shape = ExpressiveAsymmetricShape2
                ) {
                    HelpHtmlView(
                        html = tabContents[page],
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * One help topic. Its links open in the browser and are reachable by screen readers.
 */
@Composable
private fun HelpHtmlView(
    html: Spanned,
    modifier: Modifier = Modifier
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val annotatedText = remember(html, linkColor, contentColor) {
        spannedToAnnotatedString(html, linkColor, contentColor)
    }

    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    )
}

private fun spannedToAnnotatedString(
    spanned: Spanned,
    linkColor: Color,
    defaultColor: Color
): AnnotatedString {
    val text = spanned.toString()
    val builder = AnnotatedString.Builder(text)
    builder.addStyle(SpanStyle(color = defaultColor), 0, text.length)

    spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
        val start = spanned.getSpanStart(span).coerceAtLeast(0)
        val end = spanned.getSpanEnd(span).coerceAtMost(text.length)
        if (start >= end) return@forEach

        when (span) {
            is StyleSpan -> {
                when (span.style) {
                    Typeface.BOLD -> {
                        builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    }
                    Typeface.ITALIC -> {
                        builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    }
                    Typeface.BOLD_ITALIC -> {
                        builder.addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                            start,
                            end
                        )
                    }
                }
            }

            is UnderlineSpan -> {
                builder.addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    start,
                    end
                )
            }

            is ForegroundColorSpan -> {
                builder.addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
            }

            is URLSpan -> {
                builder.addLink(
                    LinkAnnotation.Url(
                        url = span.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    ),
                    start,
                    end
                )
            }

            // Headings are enlarged this way; without it they were only bold, at body size.
            is RelativeSizeSpan -> {
                builder.addStyle(SpanStyle(fontSize = span.sizeChange.em), start, end)
            }

            is TypefaceSpan -> {
                if (span.family == "monospace") {
                    builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                }
            }
        }
    }

    return builder.toAnnotatedString()
}

private fun readRawResource(context: android.content.Context, @RawRes resourceId: Int): String {
    context.resources.openRawResource(resourceId).use { inputStream: InputStream ->
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            val content = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                content.append(line)
                line = reader.readLine()
            }
            return content.toString()
        }
    }
}
