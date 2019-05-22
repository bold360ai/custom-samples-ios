package nanorep.com.botdemo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.integration.core.StateEvent
import com.nanorep.convesationui.structure.FriendlyDatestampFormatFactory
import com.nanorep.convesationui.structure.controller.ChatController
import com.nanorep.convesationui.structure.controller.ChatEventListener
import com.nanorep.convesationui.structure.controller.ChatLoadResponse
import com.nanorep.convesationui.structure.controller.ChatLoadedListener
import com.nanorep.nanoengine.Account
import com.nanorep.nanoengine.AccountInfo
import com.nanorep.nanoengine.BotAccount
import com.nanorep.nanoengine.model.configuration.ConversationSettings
import com.nanorep.nanoengine.model.configuration.TimestampStyle
import com.nanorep.sdkcore.utils.NRError
import com.nanorep.sdkcore.utils.snack
import com.nanorep.sdkcore.utils.toast
import kotlinx.android.synthetic.main.activity_main.*
import nanorep.com.botdemo.fragments.DemoMainFragment
import nanorep.com.botdemo.fragments.DemoMainFragment_TAG
import nanorep.com.botdemo.fragments.DummyInAppFragment
import nanorep.com.botdemo.fragments.DummyInAppFragment_TAG
import nanorep.com.botdemo.providers.MyAccountProvider
import nanorep.com.botdemo.providers.MyEntitiesProvider
import nanorep.com.botdemo.providers.MyHistoryProvider

interface ChatFlowHandler : ChatEventListener {
    fun waitingVisibility(visible: Boolean)
    fun onAccountReady(account: Account)
}

class MainActivity : AppCompatActivity(), ChatFlowHandler {

    private var chatController: ChatController? = null

    // Providers to be passed to the chat controller
    private val accountProvider = MyAccountProvider()
    private var entitiesProvider = MyEntitiesProvider()
    private var historyProvider = MyHistoryProvider()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        supportFragmentManager.beginTransaction()
            .replace(R.id.content_main, DemoMainFragment.newInstance(),
                DemoMainFragment_TAG
            )
            .commit()
    }

    override fun onStop() {
        if (isFinishing) {
            clearAllResources();
        }

        super.onStop();
    }

    override fun onBackPressed() {
        waitingVisibility(false)
        super.onBackPressed()
    }

    private fun clearAllResources() {
        try {
            chatController?.run {
                terminateChat()
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override fun onDestroy() {
        clearAllResources()
        super.onDestroy()
    }

    override fun waitingVisibility(visible: Boolean) {
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onAccountReady(account: Account) {
        waitingVisibility(true)

        accountProvider.updateAccountInfo(account)

        // Register to the account's MissingEntities
        (account as? BotAccount)?.entities = arrayOf("CREDIT_CARD", "ACCOUNT")

        historyProvider.accountId = account.getApiKey() + (account as? BotAccount)?.contexts

        if (isFinishing) return

        chatController = createChat(account)

    }

    override fun onAccountUpdate(accountInfo: AccountInfo) {
        accountProvider.updateAccountInfo(accountInfo)
    }

    /**
     * Initializes the chat controller with the providers and opens the main fragment
     */
    private fun createChat(account: Account): ChatController? {
        if (isFinishing) return null

        val settings = ConversationSettings()
            .disableFileUpload()
            .speechEnable(true)
            .enableMultiRequestsOnLiveAgent(true)
            .timestampConfig(
                true, TimestampStyle(
                    "dd.MM hh:mm:ss",
                    10, Color.parseColor("#33aa33"), null
                )
            )
            .datestamp(true, FriendlyDatestampFormatFactory(this))

        return ChatController.Builder(this).apply {
            conversationSettings(settings)
            chatEventListener(this@MainActivity)
            entitiesProvider(entitiesProvider)
            accountProvider.run { accountProvider(this) }
            historyProvider.run { historyProvider(this) }
        }
            .build(account, object : ChatLoadedListener {

                override fun onComplete(result: ChatLoadResponse) {

                    val error = result.error

                    if (error != null) {
                        onError(error)
                        if (!(error.isConversationError() || error.isServerConnectionNotAvailable())) {
                            openConversationFragment(result.fragment)
                        }

                    } else {
                        openConversationFragment(result.fragment)
                    }

                    waitingVisibility(false)
                }
            })
    }

    /**
     * Mostly being used for live chat
     */
    override fun onChatStateChanged(stateEvent: StateEvent) {

        Log.d(DemoMainFragment_TAG, "onChatStateChanged: state " + stateEvent.state + ", scope = " + stateEvent.scope)

        when (stateEvent.state) {

            StateEvent.Preparing -> {
            }

            StateEvent.Started -> {
            }

            StateEvent.InQueue -> {
            }

            StateEvent.Pending -> {
            }

            StateEvent.ChatWindowLoaded -> {
            }

            StateEvent.Unavailable -> {
            }

            StateEvent.Ending, StateEvent.Ended -> {
            }

            StateEvent.ChatWindowDetached -> {
            }
        }
    }

    private fun openConversationFragment(fragment: Fragment) {
        if (isFinishing || supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(CONVERSATION_FRAGMENT_TAG) != null) {
            return
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.content_main, fragment, CONVERSATION_FRAGMENT_TAG)
            .addToBackStack(CONVERSATION_FRAGMENT_TAG)
            .commit()
    }

    private fun openInAppFragment() {

        hideKeyboard()

        supportFragmentManager.beginTransaction()
            .add(R.id.content_main, DummyInAppFragment.newInstance(),
                DummyInAppFragment_TAG
            )
            .addToBackStack(null)
            .commit()
    }

/////////////////////////////////////////////////////// Error handling:

    @SuppressLint("ResourceType")
    override fun onError(error: NRError) {

        val reason = error.reason

        when (error.errorCode) {

            NRError.ConversationCreationError -> {

                notifyConversationError(error)

                if (reason != null && reason == NRError.ConnectionException) {
                    notifyConnectionError()
                }
            }

            NRError.StatementError ->

                if (error.isConversationError()) {
                    notifyConversationError(error)

                } else {
                    notifyStatementError(error)
                }

            else -> {
                /*all other errors will be handled here. Demo implementation, displays a toast and
                  writes to the log.
                 if needed the error.getErrorCode() and sometimes the error.getReason() can provide
                 the details regarding the error
                 */
                Log.e("App-ERROR", error.toString())

                if (reason != null && reason == NRError.ConnectionException) {
                    notifyConnectionError()
                } else {
                    notifyError(error, "general error: ", Color.DKGRAY)
                }
            }
        }
    }

    private fun notifyConnectionError() {
        toast(this, "Connection failure.\nPlease check your connection.")
    }

    private fun notifyConversationError(error: NRError) {
        notifyError(error, "Conversation is not available: ", Color.parseColor("#6666aa"))
    }

    private fun notifyStatementError(error: NRError) {
        notifyError(error, "statement failure - ", Color.RED)
    }

    @SuppressLint("ResourceType")
    private fun notifyError(error: NRError, s: String, i: Int) {

        try {
            supportFragmentManager.fragments.lastOrNull()?.view?.run {
                snack(
                    s + error.reason + ": " + error.description,
                    4000, -1, Gravity.CENTER, intArrayOf(), i
                )
            }

        } catch (ignored: Exception) {
            toast(this, s + error.reason + ": " + error.description)
        }
    }

/////////////////////////////////////////////////////// User action events:


    override fun onUrlLinkSelected(url: String) {
        // sample code for handling given link
        try {
            url.takeIf { it.startsWith("nanorep://#") }?.apply {

                openInAppFragment()

            }  ?: run {
                val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse(url))
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(CONVERSATION_FRAGMENT_TAG, ">> Failed to activate link on default app: " + e.message)
            // toast(this, "activating: $url", Toast.LENGTH_SHORT)
        }
    }

    //-> previous listener method signature @Override onPhoneNumberNavigation(@NonNull String phoneNumber) {
    override fun onPhoneNumberSelected(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:$phoneNumber")
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(CONVERSATION_FRAGMENT_TAG, ">> Failed to activate phone dialer default app: " + e.message)
        }
    }

///////////////////////////////////////////////////////

    companion object {
        const val CONVERSATION_FRAGMENT_TAG = "conversation_fragment"
    }

    private fun hideKeyboard() {
        (getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.takeIf { currentFocus != null }?.run {
            hideSoftInputFromWindow(currentFocus.windowToken, 0)
        }
    }
}
