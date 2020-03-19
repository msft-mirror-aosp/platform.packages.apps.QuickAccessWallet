/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.plugin.globalactions.wallet;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletCard;
import android.service.quickaccesswallet.WalletServiceEvent;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.plugins.GlobalActionsPanelPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WalletPanelViewController implements
        GlobalActionsPanelPlugin.PanelViewController,
        WalletCardCarousel.OnSelectionListener,
        QuickAccessWalletClient.OnWalletCardsRetrievedCallback,
        QuickAccessWalletClient.WalletServiceEventListener {

    private static final String TAG = "WalletPanelViewCtrl";
    private static final int MAX_CARDS = 10;
    private static final long SELECTION_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final Context mPluginContext;
    private final QuickAccessWalletClient mWalletClient;
    private final WalletView mWalletView;
    private final WalletCardCarousel mWalletCardCarousel;
    private final GlobalActionsPanelPlugin.Callbacks mPluginCallbacks;
    private final ExecutorService mExecutor;
    private final Handler mHandler;
    private final Runnable mSelectionRunnable = this::selectCard;
    private boolean mIsDeviceLocked;
    private boolean mIsDismissed;
    private boolean mHasRegisteredListener;
    private String mSelectedCardId;

    public WalletPanelViewController(
            Context pluginContext,
            QuickAccessWalletClient walletClient,
            GlobalActionsPanelPlugin.Callbacks pluginCallbacks,
            boolean isDeviceLocked) {
        mPluginContext = pluginContext;
        mWalletClient = walletClient;
        mPluginCallbacks = pluginCallbacks;
        mIsDeviceLocked = isDeviceLocked;
        mWalletView = new WalletView(pluginContext);
        mWalletView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT));
        mWalletCardCarousel = mWalletView.getCardCarousel();
        mWalletCardCarousel.setSelectionListener(this);
        mHandler = new Handler(Looper.myLooper());
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.PanelViewController}. Returns the {@link View}
     * containing the Quick Access Wallet.
     */
    @Override
    public View getPanelContent() {
        return mWalletView;
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.PanelViewController}. Invoked when the view
     * containing the Quick Access Wallet is dismissed.
     */
    @Override
    public void onDismissed() {
        if (mIsDismissed) {
            return;
        }
        mIsDismissed = true;
        mSelectedCardId = null;
        mHandler.removeCallbacks(mSelectionRunnable);
        mWalletClient.notifyWalletDismissed();
        mWalletClient.removeWalletServiceEventListener(this);
        mWalletView.animateDismissal();
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.PanelViewController}. Invoked when the device is
     * either locked or unlocked while the wallet is visible.
     */
    @Override
    public void onDeviceLockStateChanged(boolean deviceLocked) {
        if (mIsDismissed || mIsDeviceLocked == deviceLocked) {
            // Disregard repeat events
            return;
        }
        mIsDeviceLocked = deviceLocked;
        // Cards are re-queried because the wallet application may wish to change card art, icons,
        // text, or other attributes depending on the lock state of the device.
        queryWalletCards();
    }

    /**
     * Query wallet cards from the client and display them on screen.
     */
    void queryWalletCards() {
        if (mIsDismissed) {
            return;
        }
        if (!mHasRegisteredListener) {
            // Listener is registered even when device is locked. Should only be registered once.
            mWalletClient.addWalletServiceEventListener(this);
            mHasRegisteredListener = true;
        }
        if (mIsDeviceLocked && !mWalletClient.isWalletFeatureAvailableWhenDeviceLocked()) {
            mWalletView.showDeviceLockedMessage();
            return;
        }
        mWalletView.hideErrorMessage();
        int cardWidthPx = mWalletCardCarousel.getCardWidthPx();
        int cardHeightPx = mWalletCardCarousel.getCardHeightPx();
        int iconSizePx = mWalletView.getIconSizePx();
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidthPx, cardHeightPx, iconSizePx, MAX_CARDS);
        mWalletClient.getWalletCards(mExecutor, request, this);
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when cards
     * are retrieved successfully from the service. This is called on {@link #mExecutor}.
     */
    @Override
    public void onWalletCardsRetrieved(GetWalletCardsResponse response) {
        List<WalletCard> walletCards = response.getWalletCards();
        // TODO: if walletCards is empty, show empty state view
        List<WalletCardViewInfo> data = new ArrayList<>(walletCards.size());
        for (WalletCard card : walletCards) {
            data.add(new QAWalletCardViewInfo(card));
        }
        // Get on main thread for UI updates
        mWalletView.post(() -> {
            if (mIsDismissed) {
                return;
            }
            boolean animate = mWalletCardCarousel.setData(data, response.getSelectedIndex());
            mWalletView.showCardCarousel(animate);
        });
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when there
     * is an error during card retrieval. This will be run on the {@link #mExecutor}.
     */
    @Override
    public void onWalletCardRetrievalError(GetWalletCardsError error) {
        mWalletView.post(() -> {
            if (mIsDismissed) {
                return;
            }
            mWalletView.showErrorMessage(error.getMessage());
        });
    }

    /**
     * Implements {@link QuickAccessWalletClient.WalletServiceEventListener}. Called when the wallet
     * application propagates an event, such as an NFC tap, to the quick access wallet view.
     */
    @Override
    public void onWalletServiceEvent(WalletServiceEvent event) {
        if (mIsDismissed) {
            return;
        }
        switch (event.getEventType()) {
            case WalletServiceEvent.TYPE_NFC_PAYMENT_STARTED:
                mPluginCallbacks.dismissGlobalActionsMenu();
                onDismissed();
                break;
            case WalletServiceEvent.TYPE_WALLET_CARDS_UPDATED:
                queryWalletCards();
                break;
            default:
                Log.w(TAG, "onWalletServiceEvent: Unknown event type");
        }
    }

    /**
     * Implements {@link WalletCardCarousel.OnSelectionListener}. Called when the user selects a
     * card from the carousel by scrolling to it.
     */
    @Override
    public void onCardSelected(WalletCardViewInfo card) {
        if (mIsDismissed) {
            return;
        }
        mSelectedCardId = card.getCardId();
        selectCard();
    }

    /**
     * Implements {@link WalletCardCarousel.OnSelectionListener}. Called when the user taps on the
     * view outside of a card target which should cause the wallet to be dismissed.
     */
    @Override
    public void onDismissGesture() {
        if (mIsDismissed) {
            return;
        }
        mPluginCallbacks.dismissGlobalActionsMenu();
        onDismissed();
    }

    private void selectCard() {
        mHandler.removeCallbacks(mSelectionRunnable);
        String selectedCardId = mSelectedCardId;
        if (mIsDismissed || selectedCardId == null) {
            return;
        }
        mWalletClient.selectWalletCard(new SelectWalletCardRequest(selectedCardId));
        // Re-selecting the card keeps the connection bound so we continue to get service events
        // even if the user keeps it open for a long time.
        mHandler.postDelayed(mSelectionRunnable, SELECTION_DELAY_MILLIS);
    }

    /**
     * Implements {@link WalletCardCarousel.OnSelectionListener}. Called when the user clicks on a
     * card.
     */
    @Override
    public void onCardClicked(WalletCardViewInfo card) {
        if (mIsDismissed) {
            return;
        }
        PendingIntent pendingIntent = ((QAWalletCardViewInfo) card).mWalletCard.getPendingIntent();
        mPluginCallbacks.startPendingIntentDismissingKeyguard(pendingIntent);
        mPluginCallbacks.dismissGlobalActionsMenu();
        onDismissed();
    }

    private class QAWalletCardViewInfo implements WalletCardViewInfo {

        private final WalletCard mWalletCard;
        private final Drawable mCardDrawable;
        private final Drawable mIconDrawable;

        /**
         * Constructor is called on background executor, so it is safe to load drawables
         * synchronously.
         */
        QAWalletCardViewInfo(WalletCard walletCard) {
            mWalletCard = walletCard;
            mCardDrawable = mWalletCard.getCardImage().loadDrawable(mPluginContext);
            Icon icon = mWalletCard.getCardIcon();
            mIconDrawable = icon == null ? null : icon.loadDrawable(mPluginContext);
        }

        @Override
        public String getCardId() {
            return mWalletCard.getCardId();
        }

        @Override
        public Drawable getCardDrawable() {
            return mCardDrawable;
        }

        @Override
        public CharSequence getContentDescription() {
            return mWalletCard.getContentDescription();
        }

        @Override
        public Drawable getIcon() {
            return mIconDrawable;
        }

        @Override
        public CharSequence getText() {
            return mWalletCard.getCardLabel();
        }
    }
}
