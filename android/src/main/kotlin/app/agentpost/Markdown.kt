package app.agentpost

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val parser = Parser.builder().build()
private val Node.children: Sequence<Node>
    get() = generateSequence(firstChild) { it.next }

@Composable
fun MessageMarkdown(text: String, copy: (String) -> Unit) {
    val document = remember(text) { parser.parse(text) }
    MarkdownBlocks(document, copy)
}

@Composable
private fun MarkdownBlocks(parent: Node, copy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parent.children.forEach { block ->
            when (block) {
                is Paragraph -> InlineText(block)
                is Heading -> InlineText(block, heading = true)
                is FencedCodeBlock -> CodeBlock(block.info.orEmpty().substringBefore(' '), block.literal, copy)
                is IndentedCodeBlock -> CodeBlock("", block.literal, copy)
                is BulletList, is OrderedList -> {
                    val start = (block as? OrderedList)?.markerStartNumber ?: 1
                    block.children.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (block is OrderedList) (start + index).toString() + "." else "•")
                            MarkdownBlocks(item, copy)
                        }
                    }
                }
                is BlockQuote -> Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) { MarkdownBlocks(block, copy) }
                }
                is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                is HtmlBlock -> Text(block.literal.trim())
                else -> MarkdownBlocks(block, copy)
            }
        }
    }
}

@Composable
private fun InlineText(node: Node, heading: Boolean = false) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val value = remember(node, codeBackground) {
        buildAnnotatedString {
            fun render(current: Node) {
                when (current) {
                    is org.commonmark.node.Text -> append(current.literal)
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(current.literal)
                    }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { current.children.forEach(::render) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { current.children.forEach(::render) }
                    is SoftLineBreak -> append(" ")
                    is HardLineBreak -> append("\n")
                    is HtmlInline -> append(current.literal)
                    else -> current.children.forEach(::render)
                }
            }
            render(node)
        }
    }
    Text(value, fontSize = if (heading) 19.sp else 16.sp, lineHeight = 25.sp,
        fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun CodeBlock(language: String, code: String, copy: (String) -> Unit) {
    val mint = Color(0xffd7ebac)
    Surface(color = Color(0xff153930), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "代码" }, color = mint, fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { copy(code) }) {
                    Icon(Icons.Rounded.ContentCopy, "复制代码", tint = mint, modifier = Modifier.size(17.dp))
                }
            }
            Text(code.trimEnd('\n'), color = Color.White, fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), fontSize = 13.sp)
        }
    }
}
