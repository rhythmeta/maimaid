package org.rhythmeta.maimaid.ui.community

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.data.CommunityAliasVotingBoardItem
import org.rhythmeta.maimaid.ui.theme.AppThemeSettings
import org.rhythmeta.maimaid.ui.theme.MaimaidTheme

class CommunityAliasCandidateCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun moderationActionsFollowAdminRoleAndSubmitDistinctDecisions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isAdmin = mutableStateOf(false)
        val updating = mutableStateOf(false)
        val votes = mutableListOf<Boolean>()
        val decisions = mutableListOf<String>()
        compose.setContent {
            MaimaidTheme(settings = AppThemeSettings()) {
                CommunityAliasCandidateCard(
                    item = CommunityAliasVotingBoardItem("7", "song", "alias"),
                    isUpdating = updating.value,
                    enabled = !updating.value,
                    isAdmin = isAdmin.value,
                    onModerate = { decisions.add(it.status) },
                    onVote = { votes.add(it) },
                )
            }
        }
        val approve = compose.onNodeWithText(context.getString(R.string.community_alias_set_approved))
        val reject = compose.onNodeWithText(context.getString(R.string.community_alias_set_rejected))
        val support = compose.onNodeWithText(context.getString(R.string.community_alias_support, 0))
        val oppose = compose.onNodeWithText(context.getString(R.string.community_alias_oppose, 0))
        val cancel = compose.onNodeWithText(context.getString(R.string.action_cancel))

        support.performTouchInput { longClick() }
        approve.assertDoesNotExist()
        reject.assertDoesNotExist()
        compose.runOnIdle {
            votes.clear()
            isAdmin.value = true
        }
        support.performTouchInput { click() }
        oppose.performTouchInput { click() }
        support.performTouchInput { longClick() }
        approve.assertIsDisplayed()
        cancel.performClick()
        assertEquals(emptyList<String>(), decisions)

        support.performTouchInput { longClick() }
        approve.performClick()
        oppose.performTouchInput { longClick() }
        reject.performClick()
        assertEquals(listOf("approved", "rejected"), decisions)
        assertEquals(listOf(true, false), votes)

        compose.runOnIdle { updating.value = true }
        support.performTouchInput { longClick() }
        approve.assertDoesNotExist()
    }
}
