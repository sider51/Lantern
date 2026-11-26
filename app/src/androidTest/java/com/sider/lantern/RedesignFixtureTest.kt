package com.sider.lantern

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RedesignFixtureTest {
    @Test fun addSyntheticStoriesForVisualReview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanternUiFixture") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var library: LibraryRepository
            scenario.onActivity { library = ViewModelProvider(it)[LanternModel::class.java].library }
            runBlocking {
                check(library.externalStatus == "アプリ内") { "Use internal storage for visual fixtures" }
                library.initialize()
                val stories = library.createFolder("港町の掌編", ROOT_ID).id
                val notes = library.createFolder("物語の種", ROOT_ID).id
                library.createFolder("冬の断章", stories)
                val draft = visualReviewDraft()
                val other = listOf(
                    Document.create("夜明けまでの手紙",
                        "封筒に宛名を書くころ、外はまだ藍色だった。切手を貼らずに窓辺へ置く。鳥が鳴けば開けて読もうと思った。") to notes,
                    Document.create("風をしまう瓶",
                        "母が古い瓶を洗い、北風が吹く日にだけふたを開けると言った。棚に並ぶ瓶はどれも空に見えた。") to notes,
                    Document.create("雨粒の標本",
                        "バス停の屋根から落ちる雨を、紬はひとつずつ数えた。百を過ぎたところで、傘の下から友人が手を振った。") to stories,
                )
                for ((entry, folder) in other + (draft to stories)) {
                    library.save(entry, null, 1)
                    library.moveManuscript(entry.id, folder)
                }
            }
        }
    }
}
internal fun visualReviewDraft(): Document {
    val body = """
        汽笛が鳴る前に、凪が戻ってきた。駅舎の窓に薄い潮が乾き、誰かが指で書いた日付だけが残っている。

        「きょうは海まで行くの？」
        弟は返事をせず、ポケットから砂糖の包みを二つ出した。ひとつを私の手にのせると、軽い紙の音がした。

        あの窓を開けたら、向こうから朝が来る。小さかったころは本気でそう思っていた。いまは時刻表を読むふりをして、水平線がわずかに明るくなるのを待つ。

        「帰りには、ここで降りよう」
        言い終えるころ、線路の端で光がほどけた。始発は一両きりで、海とは反対の方角からやってきた。

        弟が窓側に座る。包みはまだ開けない。そのまま持っていたら、きょうがいつまでも始まらないような気がした。
    """.trimIndent()
    return Document.create("潮待ちの駅", body).copy(
        annotations = listOf(
            Annotation(Format.Ruby, body.indexOf("凪"), body.indexOf("凪") + 1, "なぎ"),
            Annotation(Format.Emphasis, body.indexOf("あの窓"), body.indexOf("あの窓") + "あの窓".length),
        ),
        pageBreaks = listOf(body.indexOf("「帰りには")),
    )
}
