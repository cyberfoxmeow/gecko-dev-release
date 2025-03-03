/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.messaging.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.MiddlewareContext
import mozilla.components.service.nimbus.messaging.Message
import mozilla.components.service.nimbus.messaging.NimbusMessagingControllerInterface
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.ConsumeMessageToShow
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.Evaluate
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.MessageClicked
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.MessageDismissed
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.MicrosurveyAction
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.Restore
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.UpdateMessageToShow
import org.mozilla.fenix.components.appstate.AppAction.MessagingAction.UpdateMessages
import org.mozilla.fenix.components.appstate.AppState
import org.mozilla.fenix.utils.Settings

typealias AppStoreMiddlewareContext = MiddlewareContext<AppState, AppAction>

class MessagingMiddleware(
    private val controller: NimbusMessagingControllerInterface,
    private val settings: Settings,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : Middleware<AppState, AppAction> {

    override fun invoke(
        context: AppStoreMiddlewareContext,
        next: (AppAction) -> Unit,
        action: AppAction,
    ) {
        when (action) {
            is Restore -> {
                coroutineScope.launch {
                    val messages = controller.getMessages()
                    context.store.dispatch(UpdateMessages(messages))
                }
            }

            is Evaluate -> {
                val message = controller.getNextMessage(
                    action.surface,
                    context.state.messaging.messages,
                )
                if (message != null) {
                    context.store.dispatch(UpdateMessageToShow(message))
                    onMessagedDisplayed(message, context)
                } else {
                    context.store.dispatch(ConsumeMessageToShow(action.surface))
                }
            }

            is MessageClicked -> onMessageClicked(action.message, context)

            is MessageDismissed -> onMessageDismissed(context, action.message)

            is MicrosurveyAction.Shown -> onMicrosurveyShown(action.id)

            is MicrosurveyAction.OnPrivacyNoticeTapped -> onPrivacyNoticeTapped(action.id)

            is MicrosurveyAction.Dismissed -> {
                context.store.state.messaging.messages.find { it.id == action.id }?.let { message ->
                    onMicrosurveyDismissed(context, message)
                }
            }

            is MicrosurveyAction.Completed -> {
                context.store.state.messaging.messages.find { it.id == action.id }?.let { message ->
                    onMicrosurveyCompleted(context, message, action.answer)
                }
            }

            is MicrosurveyAction.SentConfirmationShown -> onMicrosurveyConfirmationShown(action.id)

            is MicrosurveyAction.Started -> onMicrosurveyStarted(action.id)

            else -> {
                // no-op
            }
        }
        next(action)
    }

    private fun onMicrosurveyCompleted(
        context: AppStoreMiddlewareContext,
        message: Message,
        answer: String,
    ) {
        val newMessages = removeMessage(context, message)
        context.store.dispatch(UpdateMessages(newMessages))
        consumeMessageToShowIfNeeded(context, message)
        coroutineScope.launch {
            controller.onMicrosurveyCompleted(message, answer)
        }
    }

    private fun onMicrosurveyShown(id: String) {
        coroutineScope.launch {
            controller.onMicrosurveyShown(id)
        }
    }

    private fun onMicrosurveyDismissed(
        context: AppStoreMiddlewareContext,
        message: Message,
    ) {
        val newMessages = removeMessage(context, message)
        context.store.dispatch(UpdateMessages(newMessages))
        consumeMessageToShowIfNeeded(context, message)
        coroutineScope.launch {
            controller.onMicrosurveyDismissed(message)
        }
    }

    private fun onMicrosurveyConfirmationShown(id: String) {
        coroutineScope.launch {
            controller.onMicrosurveySentConfirmationShown(id)
        }
    }

    private fun onPrivacyNoticeTapped(id: String) {
        coroutineScope.launch {
            controller.onMicrosurveyPrivacyNoticeTapped(id)
        }
    }

    private fun onMessagedDisplayed(
        oldMessage: Message,
        context: AppStoreMiddlewareContext,
    ) {
        coroutineScope.launch {
            val newMessage = controller.onMessageDisplayed(oldMessage)
            val newMessages = if (!newMessage.isExpired) {
                updateMessage(context, oldMessage, newMessage)
            } else {
                if (newMessage.isMicrosurvey()) settings.shouldShowMicrosurveyPrompt = false
                consumeMessageToShowIfNeeded(context, oldMessage)
                removeMessage(context, oldMessage)
            }
            context.store.dispatch(UpdateMessages(newMessages))
        }
    }

    private fun Message.isMicrosurvey() = surface == "microsurvey"

    private fun onMessageDismissed(
        context: AppStoreMiddlewareContext,
        message: Message,
    ) {
        val newMessages = removeMessage(context, message)
        context.store.dispatch(UpdateMessages(newMessages))
        consumeMessageToShowIfNeeded(context, message)
        coroutineScope.launch {
            controller.onMessageDismissed(message)
        }
    }

    private fun onMessageClicked(
        message: Message,
        context: AppStoreMiddlewareContext,
    ) {
        // Update Nimbus storage.
        coroutineScope.launch {
            controller.onMessageClicked(message)
        }
        // Update app state.
        val newMessages = removeMessage(context, message)
        context.store.dispatch(UpdateMessages(newMessages))
        consumeMessageToShowIfNeeded(context, message)
    }

    private fun onMicrosurveyStarted(
        id: String,
    ) {
        coroutineScope.launch {
            controller.onMicrosurveyStarted(id)
        }
    }

    private fun consumeMessageToShowIfNeeded(
        context: AppStoreMiddlewareContext,
        message: Message,
    ) {
        val current = context.state.messaging.messageToShow[message.surface]
        if (current?.id == message.id) {
            context.store.dispatch(ConsumeMessageToShow(message.surface))
        }
    }

    private fun removeMessage(
        context: AppStoreMiddlewareContext,
        message: Message,
    ): List<Message> {
        return context.state.messaging.messages.filter { it.id != message.id }
    }

    private fun updateMessage(
        context: AppStoreMiddlewareContext,
        oldMessage: Message,
        updatedMessage: Message,
    ): List<Message> {
        val actualMessageToShow = context.state.messaging.messageToShow[updatedMessage.surface]

        if (actualMessageToShow?.id == oldMessage.id) {
            context.store.dispatch(UpdateMessageToShow(updatedMessage))
        }
        val oldMessageIndex = context.state.messaging.messages.indexOfFirst { it.id == updatedMessage.id }

        return if (oldMessageIndex != -1) {
            val newList = context.state.messaging.messages.toMutableList()
            newList[oldMessageIndex] = updatedMessage
            newList
        } else {
            // No need to update the message, it was removed. This is due to a race condition, see:
            // https://bugzilla.mozilla.org/show_bug.cgi?id=1897485
            context.state.messaging.messages
        }
    }
}
