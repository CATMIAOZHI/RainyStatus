package com.rainy.status.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainy.status.R
import com.rainy.status.domain.history.MoodEvent
import com.rainy.status.ui.theme.StrawberryPink
import com.rainy.status.ui.theme.inkMuted
import com.rainy.status.util.DurationFormatter

/**
 * 首页「最近的心情」卡片。
 *
 * 调用点在「本机心情记录开关打开」时；关着时**整块不渲染**，而不是显示一句「未开启」——
 * 设置页已经有那个开关，首页再劝一次只是噪音。
 *
 * 刻意只显示最近几条、每条压成一行：
 * 1. 首页已经有 5 张卡，铺开一整屏的碎碎念会把「保活检查」推到很深的滚动位置；
 * 2. 这是**本机**回顾，不是公开页，手机递给别人看电量时不该顺便被翻旧账。
 *    要看全就点卡片底部的「查看全部」，回看本来就是低频动作。
 *
 * [onOpenAll] 只在真有记录时才显示：空卡片再给一个「查看全部」只会通向一个空页面。
 */
@Composable
fun MoodRecentCard(events: List<MoodEvent>, onOpenAll: () -> Unit) {
    val context = LocalContext.current
    // 每次重组取一次「现在」，相对时间才会随停留时长自己走（与 HistoryCard 同一做法）
    val now = System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.home_mood_recent_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_mood_recent_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = inkMuted()
                )
            } else {
                events.forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            // 一条记录一次朗读：不合并的话读屏会先念表情、再念文字、再念
                            // 「5 分钟前」，3 条就是 9 个停靠点，孤零零的「😊」没有上下文
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val emoji = event.emoji
                        if (!emoji.isNullOrEmpty()) {
                            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        // 140 码点（最多 280 个 UTF-16 单元）在这里靠省略号收口，
                        // 不手工 substring —— 那会把 emoji 的代理对切成半个字符
                        Text(
                            text = event.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            // 与通知栏共用同一套「刚刚 / N 分钟前 / …」，两处不会各说各话
                            text = DurationFormatter.format(context, now - event.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = inkMuted()
                        )
                    }
                }
            }
            if (events.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 48dp 是最小可点高度：这行文字本身只有一行高，光靠文字很难点中
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenAll),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mood_history_open_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StrawberryPink
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        // 没有 contentDescription：整行有可见文字，图标再说一遍只是噪音
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = StrawberryPink,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}