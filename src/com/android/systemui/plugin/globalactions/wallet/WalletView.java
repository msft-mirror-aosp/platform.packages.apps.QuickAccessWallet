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

import static com.android.systemui.plugin.globalactions.wallet.WalletCardCarousel.CARD_ANIM_ALPHA_DURATION;
import static com.android.systemui.plugin.globalactions.wallet.WalletCardCarousel.CARD_ANIM_ALPHA_DELAY;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;


/**
 * Contains the entire wallet view. Coordinates the label text with the currently selected card in
 * the contained carousel. Also capable of showing the lock screen error message.
 */
class WalletView extends FrameLayout implements WalletCardCarousel.OnCardScrollListener {

    private static final int CAROUSEL_IN_ANIMATION_DURATION = 300;
    private static final int CAROUSEL_OUT_ANIMATION_DURATION = 200;
    private static final int CARD_LABEL_ANIM_DELAY = 133;
    private final ViewGroup mCardCarouselContainer;
    private final WalletCardCarousel mCardCarousel;
    private final TextView mCardLabel;
    private final TextView mErrorView;
    private final int mIconSizePx;
    private final Interpolator mInInterpolator;
    private final Interpolator mOutInterpolator;
    private final float mAnimationTranslationX;
    private CharSequence mCenterCardText;

    public WalletView(Context context) {
        this(context, null);
    }

    public WalletView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.wallet_view, this);
        mCardCarouselContainer = findViewById(R.id.CardCarouselContainer);
        mCardCarousel = findViewById(R.id.CardCarousel);
        mCardCarousel.setCardScrollListener(this);
        mCardLabel = findViewById(R.id.CardLabel);
        mErrorView = findViewById(R.id.ErrorView);
        mIconSizePx = getResources().getDimensionPixelSize(R.dimen.icon_size);
        mInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mOutInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.accelerate_cubic);
        mAnimationTranslationX = mCardCarousel.getCardWidthPx() / 4f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Forward touch events to card carousel to allow for swiping outside carousel bounds.
        return mCardCarousel.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public void onCardScroll(WalletCardViewInfo centerCard, WalletCardViewInfo nextCard,
            float percentDistanceFromCenter) {
        CharSequence centerCardText = centerCard.getText();
        if (!TextUtils.equals(mCenterCardText, centerCardText)) {
            mCenterCardText = centerCardText;
            mCardLabel.setText(centerCardText);
            int iconSizePx = mCardLabel.getLineHeight();
            Drawable icon = centerCard.getIcon();
            if (icon != null) {
                icon.setBounds(0, 0, iconSizePx, iconSizePx);
            }
            mCardLabel.setCompoundDrawablesRelative(icon, null, null, null);
        }
        if (TextUtils.equals(centerCardText, nextCard.getText())) {
            mCardLabel.setAlpha(1f);
        } else {
            mCardLabel.setAlpha(percentDistanceFromCenter);
        }
    }

    void showCardCarousel(boolean shouldAnimate) {
        mCardCarouselContainer.setVisibility(VISIBLE);
        mErrorView.setVisibility(GONE);
        if (shouldAnimate) {
            mCardLabel.setAlpha(0f);
            mCardLabel.animate().alpha(1f)
                    .setStartDelay(CARD_LABEL_ANIM_DELAY)
                    .setDuration(CARD_ANIM_ALPHA_DURATION)
                    .start();
            mCardCarousel.setTranslationX(mAnimationTranslationX);
            mCardCarousel.animate().translationX(0)
                    .setInterpolator(mInInterpolator)
                    .setDuration(CAROUSEL_IN_ANIMATION_DURATION)
                    .start();
        }
    }

    void animateDismissal() {
        mCardCarousel.animate().translationX(mAnimationTranslationX)
                .setInterpolator(mOutInterpolator)
                .setDuration(CAROUSEL_OUT_ANIMATION_DURATION)
                .start();
        mCardCarouselContainer.animate()
                .alpha(0f)
                .setDuration(CARD_ANIM_ALPHA_DURATION)
                .setStartDelay(CARD_ANIM_ALPHA_DELAY)
                .start();
    }

    void showDeviceLockedMessage() {
        showErrorMessage(getResources().getText(R.string.error_user_locked));
    }

    void showErrorMessage(@Nullable CharSequence message) {
        if (TextUtils.isEmpty(message)) {
            message = getResources().getText(R.string.error_generic);
        }
        mErrorView.setText(message);
        mErrorView.setVisibility(VISIBLE);
        mCardCarouselContainer.setVisibility(GONE);
    }

    void hideErrorMessage() {
        mErrorView.setVisibility(GONE);
    }

    WalletCardCarousel getCardCarousel() {
        return mCardCarousel;
    }

    TextView getErrorView() {
        return mErrorView;
    }

    ViewGroup getCardCarouselContainer() {
        return mCardCarouselContainer;
    }

    int getIconSizePx() {
        return mIconSizePx;
    }
}
