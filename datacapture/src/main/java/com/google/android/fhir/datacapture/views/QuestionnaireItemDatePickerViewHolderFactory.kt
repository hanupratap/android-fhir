/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.views

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentResultListener
import com.google.android.fhir.datacapture.R
import com.google.android.material.textfield.TextInputEditText
import com.google.fhir.r4.core.Date
import com.google.fhir.r4.core.QuestionnaireResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object QuestionnaireItemDatePickerViewHolderFactory :
  QuestionnaireItemViewHolderFactory(R.layout.questionnaire_item_date_picker_view) {
  override fun getQuestionnaireItemViewHolderDelegate() =
    object : QuestionnaireItemViewHolderDelegate {
      private lateinit var textDateQuestion: TextView
      private lateinit var textInputEditText: TextInputEditText
      private lateinit var questionnaireItemViewItem: QuestionnaireItemViewItem

      override fun init(itemView: View) {
        textDateQuestion = itemView.findViewById(R.id.question)
        textInputEditText = itemView.findViewById(R.id.textInputEditText)
        // Disable direct text input to only allow input from the date picker dialog
        textInputEditText.keyListener = null
        textInputEditText.setOnFocusChangeListener { _: View, hasFocus: Boolean ->
          // Do not show the date picker dialog when losing focus.
          if (!hasFocus) return@setOnFocusChangeListener

          // The application is wrapped in a ContextThemeWrapper in QuestionnaireFragment
          // and again in TextInputEditText during layout inflation. As a result, it is
          // necessary to access the base context twice to retrieve the application object
          // from the view's context.
          val context = itemView.context.tryUnwrapContext()!!
          context.supportFragmentManager.setFragmentResultListener(
            DatePickerFragment.RESULT_REQUEST_KEY,
            context,
            object : FragmentResultListener {
              // java.time APIs can be used with desugaring
              @SuppressLint("NewApi")
              override fun onFragmentResult(requestKey: String, result: Bundle) {
                val year = result.getInt(DatePickerFragment.RESULT_BUNDLE_KEY_YEAR)
                val month = result.getInt(DatePickerFragment.RESULT_BUNDLE_KEY_MONTH)
                val dayOfMonth = result.getInt(DatePickerFragment.RESULT_BUNDLE_KEY_DAY_OF_MONTH)
                val zonedDateTime =
                  LocalDate.of(
                      year,
                      // Month values are 1-12 in java.time but 0-11 in
                      // DatePickerDialog.
                      month + 1,
                      dayOfMonth
                    )
                    .atStartOfDay()
                    .atZone(ZoneId.systemDefault())
                textInputEditText.setText(zonedDateTime.format(LOCAL_DATE_FORMATTER))

                val date =
                  Date.newBuilder()
                    .setValueUs(zonedDateTime.toEpochSecond() * NUMBER_OF_MICROSECONDS_PER_SECOND)
                    .setPrecision(Date.Precision.DAY)
                    .setTimezone(ZoneId.systemDefault().id)
                    .build()
                questionnaireItemViewItem.singleAnswerOrNull =
                  QuestionnaireResponse.Item.Answer.newBuilder().apply {
                    value =
                      QuestionnaireResponse.Item.Answer.ValueX.newBuilder().setDate(date).build()
                  }
                questionnaireItemViewItem.questionnaireResponseItemChangedCallback()
              }
            }
          )
          DatePickerFragment().show(context.supportFragmentManager, DatePickerFragment.TAG)
          // Clear focus so that the user can refocus to open the dialog
          textDateQuestion.clearFocus()
        }
      }

      @SuppressLint("NewApi") // java.time APIs can be used due to desugaring
      override fun bind(questionnaireItemViewItem: QuestionnaireItemViewItem) {
        this.questionnaireItemViewItem = questionnaireItemViewItem
        textDateQuestion.text = questionnaireItemViewItem.questionnaireItem.text.value
        textInputEditText.setText(
          questionnaireItemViewItem
            .singleAnswerOrNull
            ?.value
            ?.date
            ?.let {
              Instant.ofEpochMilli(it.valueUs / NUMBER_OF_MICROSECONDS_PER_MILLISECOND)
                .atZone(ZoneId.systemDefault())
            }
            ?.format(LOCAL_DATE_FORMATTER)
            ?: ""
        )
      }
    }

  @SuppressLint("NewApi") // java.time APIs can be used due to desugaring
  val LOCAL_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE!!
}

const val NUMBER_OF_MICROSECONDS_PER_SECOND = 1000000
const val NUMBER_OF_MICROSECONDS_PER_MILLISECOND = 1000

/**
 * Returns the [AppCompatActivity] if there exists one wrapped inside [ContextThemeWrapper] s, or
 * `null` otherwise.
 *
 * This function is inspired by the function with the same name in `AppCompateDelegateImpl`. See
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:appcompat/appcompat/src/main/java/androidx/appcompat/app/AppCompatDelegateImpl.java;l=1615
 *
 * TODO: find a more robust way to do this as it is not guaranteed that the activity is an
 * AppCompatActivity.
 */
internal fun Context.tryUnwrapContext(): AppCompatActivity? {
  var context = this
  while (true) {
    when (context) {
      is AppCompatActivity -> return context
      is ContextThemeWrapper -> context = context.baseContext
      else -> return null
    }
  }
}
