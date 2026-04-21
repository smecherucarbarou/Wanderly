package com.novahorizon.wanderly.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.databinding.FragmentOnboardingBinding
import com.novahorizon.wanderly.databinding.ItemOnboardingPageBinding

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private val pages = listOf(
        OnboardingPage(
            illustrationRes = R.drawable.ic_buzzy,
            titleRes = R.string.onboarding_slide_one_title,
            subtitleRes = R.string.onboarding_slide_one_subtitle
        ),
        OnboardingPage(
            illustrationRes = R.drawable.ic_streak_fire,
            titleRes = R.string.onboarding_slide_two_title,
            subtitleRes = R.string.onboarding_slide_two_subtitle
        ),
        OnboardingPage(
            illustrationRes = R.drawable.ic_honeycomb,
            titleRes = R.string.onboarding_slide_three_title,
            subtitleRes = R.string.onboarding_slide_three_subtitle
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.onboardingPager.adapter = OnboardingPageAdapter(pages)
        TabLayoutMediator(binding.onboardingIndicator, binding.onboardingPager) { _, _ -> }.attach()

        binding.skipButton.setOnClickListener { completeOnboarding() }
        binding.nextButton.setOnClickListener {
            val isLastPage = binding.onboardingPager.currentItem == pages.lastIndex
            if (isLastPage) {
                completeOnboarding()
            } else {
                binding.onboardingPager.currentItem += 1
            }
        }

        binding.onboardingPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updateActions(position)
                }
            }
        )
        updateActions(binding.onboardingPager.currentItem)
    }

    private fun updateActions(position: Int) {
        val isLastPage = position == pages.lastIndex
        binding.skipButton.visibility = if (isLastPage) View.INVISIBLE else View.VISIBLE
        binding.nextButton.text = getString(
            if (isLastPage) R.string.onboarding_finish_button else R.string.onboarding_next_button
        )
    }

    private fun completeOnboarding() {
        WanderlyGraph.repository(requireContext()).setOnboardingSeen(true)
        findNavController().navigate(R.id.action_onboarding_to_map)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class OnboardingPage(
    @DrawableRes val illustrationRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int
)

private class OnboardingPageAdapter(
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingPageAdapter.OnboardingPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingPageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemOnboardingPageBinding.inflate(inflater, parent, false)
        return OnboardingPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OnboardingPageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class OnboardingPageViewHolder(
        private val binding: ItemOnboardingPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(page: OnboardingPage) {
            binding.onboardingIllustration.setImageResource(page.illustrationRes)
            binding.onboardingTitle.setText(page.titleRes)
            binding.onboardingSubtitle.setText(page.subtitleRes)
        }
    }
}
