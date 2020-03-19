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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletClient.WalletServiceEventListener;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletCard;
import android.service.quickaccesswallet.WalletServiceEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.android.systemui.plugins.GlobalActionsPanelPlugin;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.Arrays;
import java.util.List;


@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.R)
public class WalletPanelViewControllerTest {

    private static final int MAX_CARDS = 10;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    @Mock
    QuickAccessWalletClient mWalletClient;
    @Mock
    GlobalActionsPanelPlugin.Callbacks mPluginCallbacks;
    @Captor
    ArgumentCaptor<GetWalletCardsRequest> mRequestCaptor;
    @Captor
    ArgumentCaptor<QuickAccessWalletClient.OnWalletCardsRetrievedCallback> mCallbackCaptor;
    @Captor
    ArgumentCaptor<SelectWalletCardRequest> mSelectCardRequestCaptor;
    @Captor
    ArgumentCaptor<WalletServiceEventListener> mListenerCaptor;
    private WalletPanelViewController mViewController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mViewController =
                new WalletPanelViewController(mContext, mWalletClient, mPluginCallbacks, false);
        ShadowLog.stream = System.out;
    }

    /**
     * getPanelContent may be called multiple times even when shown once to user. It should always
     * return the same view and not re-request card.
     */
    @Test
    public void getPanelContent_returnsSameView() {
        View view1 = mViewController.getPanelContent();
        View view2 = mViewController.getPanelContent();

        assertThat(view1).isSameAs(view2);
        verify(mWalletClient, never()).getWalletCards(any(), any(), any());
    }

    @Test
    public void onDismissed_notifiesClientAndRemotesListener() {
        mViewController.queryWalletCards();
        verify(mWalletClient).addWalletServiceEventListener(mListenerCaptor.capture());
        WalletServiceEventListener serviceEventListener = mListenerCaptor.getValue();

        mViewController.onDismissed();

        verify(mWalletClient).notifyWalletDismissed();
        verify(mWalletClient).removeWalletServiceEventListener(serviceEventListener);
    }

    @Test
    public void onDismissed_doesNotTriggerTwice() {
        mViewController.queryWalletCards();

        mViewController.onDismissed();
        mViewController.onDismissed();

        verify(mWalletClient, times(1)).notifyWalletDismissed();
        verify(mWalletClient, times(1)).removeWalletServiceEventListener(any());
    }

    @Test
    public void onDeviceLockStateChanged_unlocked_queriesCards() {
        mViewController =
                new WalletPanelViewController(mContext, mWalletClient, mPluginCallbacks, true);
        when(mWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(false);
        mViewController.queryWalletCards();
        verify(mWalletClient, never()).getWalletCards(any(), any(), any());

        mViewController.onDeviceLockStateChanged(false);

        verify(mWalletClient).getWalletCards(any(), any(), any());
    }

    @Test
    public void onDeviceLockStateChanged_calledTwice_onlyQueriesCardsOnce() {
        mViewController =
                new WalletPanelViewController(mContext, mWalletClient, mPluginCallbacks, true);
        when(mWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(false);
        mViewController.queryWalletCards();
        verify(mWalletClient, never()).getWalletCards(any(), any(), any());  // sanity check

        // Sometimes the unlock triggers multiple events
        mViewController.onDeviceLockStateChanged(false);
        mViewController.onDeviceLockStateChanged(false);

        verify(mWalletClient).getWalletCards(any(), any(), any());
    }

    @Test
    public void queryWalletCards_registersListenerAndRequestsWalletCards() {
        mViewController.queryWalletCards();

        verify(mWalletClient).addWalletServiceEventListener(mViewController);
        verify(mWalletClient).getWalletCards(
                any(), mRequestCaptor.capture(), mCallbackCaptor.capture());
        GetWalletCardsRequest request = mRequestCaptor.getValue();
        assertThat(request.getMaxCards()).isEqualTo(MAX_CARDS);
    }

    @Test
    public void queryWalletCards_onlyRegistersListenerOnce() {
        mViewController.queryWalletCards();
        mViewController.queryWalletCards();

        verify(mWalletClient, times(1)).addWalletServiceEventListener(mViewController);
        verify(mWalletClient, times(2)).getWalletCards(any(), any(), any());
    }

    @Test
    public void queryWalletCards_deviceLocked_cardsAllowedOnLockScreen_queriesCards() {
        mViewController =
                new WalletPanelViewController(mContext, mWalletClient, mPluginCallbacks, true);
        when(mWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(true);

        mViewController.queryWalletCards();

        verify(mWalletClient).addWalletServiceEventListener(mViewController);
        verify(mWalletClient).getWalletCards(any(), any(), any());
    }

    @Test
    public void queryWalletCards_deviceLocked_cardsNotAllowedOnLockScreen_doesNotQueryCards() {
        mViewController =
                new WalletPanelViewController(mContext, mWalletClient, mPluginCallbacks, true);
        when(mWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(false);

        mViewController.queryWalletCards();

        verify(mWalletClient).addWalletServiceEventListener(mViewController);
        verify(mWalletClient, never()).getWalletCards(any(), any(), any());
    }

    @Test
    public void onWalletCardsRetrieved_showsCards() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        List<WalletCard> cards = Arrays.asList(createWalletCard("c1"), createWalletCard("c2"));
        GetWalletCardsResponse response = new GetWalletCardsResponse(cards, 0);

        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);

        WalletView view = (WalletView) mViewController.getPanelContent();
        View errorView = view.getErrorView();
        RecyclerView carouselView = view.getCardCarousel();
        assertThat(errorView.getVisibility()).isEqualTo(View.GONE);
        assertThat(carouselView.getVisibility()).isEqualTo(View.VISIBLE);
        int itemCount = carouselView.getAdapter().getItemCount();
        assertThat(itemCount).isEqualTo(2);
    }

    @Test
    public void onWalletCardsRetrieved_dismissed_doesNotShowCards() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        List<WalletCard> cards = Arrays.asList(createWalletCard("c1"), createWalletCard("c2"));
        GetWalletCardsResponse response = new GetWalletCardsResponse(cards, 0);
        mViewController.onDismissed();

        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);

        WalletView view = (WalletView) mViewController.getPanelContent();
        WalletCardCarousel carouselView = view.getCardCarousel();
        int itemCount = carouselView.getAdapter().getItemCount();
        assertThat(itemCount).isEqualTo(0);
    }

    @Test
    public void onWalletCardRetrievalError_showsErrorMessage() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        CharSequence errorMessage = "Cards unavailable";
        GetWalletCardsError error = new GetWalletCardsError(null, errorMessage);

        mCallbackCaptor.getValue().onWalletCardRetrievalError(error);

        WalletView view = (WalletView) mViewController.getPanelContent();
        TextView errorView = view.getErrorView();
        assertThat(view.getCardCarouselContainer().getVisibility()).isEqualTo(View.GONE);
        assertThat(errorView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(errorView.getText()).isEqualTo(errorMessage);
    }

    @Test
    public void onWalletServiceEvent_tapStarted_dismissesGlobalActionsMenu() {
        mViewController.queryWalletCards();
        verify(mWalletClient).addWalletServiceEventListener(mListenerCaptor.capture());
        WalletServiceEvent event =
                new WalletServiceEvent(WalletServiceEvent.TYPE_NFC_PAYMENT_STARTED);

        WalletServiceEventListener listener = mListenerCaptor.getValue();
        listener.onWalletServiceEvent(event);

        verify(mPluginCallbacks).dismissGlobalActionsMenu();
        verify(mWalletClient).removeWalletServiceEventListener(listener);
        verify(mWalletClient).notifyWalletDismissed();
    }

    @Test
    public void onWalletServiceEvent_cardsChanged_requeriesCards() {
        mViewController.queryWalletCards();
        verify(mWalletClient).addWalletServiceEventListener(mListenerCaptor.capture());
        WalletServiceEvent event =
                new WalletServiceEvent(WalletServiceEvent.TYPE_WALLET_CARDS_UPDATED);

        WalletServiceEventListener listener = mListenerCaptor.getValue();
        listener.onWalletServiceEvent(event);

        verify(mPluginCallbacks, never()).dismissGlobalActionsMenu();
        verify(mWalletClient, never()).notifyWalletDismissed();
        verify(mWalletClient, times(2)).getWalletCards(any(), any(), any());
    }

    @Test
    public void onCardSelected_selectsCardWithClient() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        List<WalletCard> cards = Arrays.asList(createWalletCard("c1"), createWalletCard("c2"));
        GetWalletCardsResponse response = new GetWalletCardsResponse(cards, 0);
        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        WalletView view = (WalletView) mViewController.getPanelContent();
        WalletCardCarousel carouselView = view.getCardCarousel();

        carouselView.scrollToPosition(1);

        verify(mWalletClient, times(2)).selectWalletCard(mSelectCardRequestCaptor.capture());
        List<SelectWalletCardRequest> selectRequests = mSelectCardRequestCaptor.getAllValues();
        // The first select happens as soon as cards are shown.
        assertThat(selectRequests.get(0).getCardId()).isEqualTo("c1");
        // The second select happens when the user scrolls to that card.
        assertThat(selectRequests.get(1).getCardId()).isEqualTo("c2");
    }

    @Test
    public void onCardClicked_startsIntent() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        List<WalletCard> cards = Arrays.asList(createWalletCard("c1"), createWalletCard("c2"));
        GetWalletCardsResponse response = new GetWalletCardsResponse(cards, 0);
        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        WalletView view = (WalletView) mViewController.getPanelContent();
        WalletCardCarousel carouselView = view.getCardCarousel();
        // layout carousel so that child views are added and card scrolling 'works'
        carouselView.measure(0, 0);
        int width = mContext.getResources().getDisplayMetrics().widthPixels;
        int height = mContext.getResources().getDisplayMetrics().heightPixels;
        carouselView.layout(0, 0, width, height / 2);
        carouselView.scrollToPosition(0);

        // Perform click on CardView
        ((ViewGroup) carouselView.getChildAt(0)).getChildAt(0).performClick();

        PendingIntent pendingIntent = cards.get(0).getPendingIntent();
        verify(mPluginCallbacks).startPendingIntentDismissingKeyguard(pendingIntent);
        verify(mPluginCallbacks).dismissGlobalActionsMenu();
        verify(mWalletClient).notifyWalletDismissed();
        verify(mWalletClient).removeWalletServiceEventListener(any());
    }

    @Test
    public void onSingleTapUp_dismissesWallet() {
        mViewController.queryWalletCards();
        verify(mWalletClient).getWalletCards(any(), mRequestCaptor.capture(),
                mCallbackCaptor.capture());
        List<WalletCard> cards = Arrays.asList(createWalletCard("c1"), createWalletCard("c2"));
        GetWalletCardsResponse response = new GetWalletCardsResponse(cards, 0);
        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        WalletView view = (WalletView) mViewController.getPanelContent();
        WalletCardCarousel carouselView = view.getCardCarousel();
        // layout carousel so that child views are added and card scrolling 'works'
        carouselView.measure(0, 0);
        int width = mContext.getResources().getDisplayMetrics().widthPixels;
        int height = mContext.getResources().getDisplayMetrics().heightPixels;
        carouselView.layout(0, 0, width, height / 2);

        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));
        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0));

        verify(mPluginCallbacks, never()).startPendingIntentDismissingKeyguard(any());
        verify(mPluginCallbacks).dismissGlobalActionsMenu();
        verify(mWalletClient).notifyWalletDismissed();
        verify(mWalletClient).removeWalletServiceEventListener(any());
    }

    private WalletCard createWalletCard(String cardId) {
        Icon cardImage = Icon.createWithBitmap(
                Bitmap.createBitmap(70, 44, Bitmap.Config.ARGB_8888));
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName("foo.bar.wallet", "foo.bar.wallet.WalletActivity"))
                .putExtra("cardId", cardId);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(cardId, cardImage, cardId + " card", pendingIntent).build();
    }
}
