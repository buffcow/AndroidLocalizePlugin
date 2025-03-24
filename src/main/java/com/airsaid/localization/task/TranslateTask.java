/*
 * Copyright 2021 Airsaid. https://github.com/airsaid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.airsaid.localization.task;

import com.airsaid.localization.constant.Constants;
import com.airsaid.localization.services.AndroidValuesService;
import com.airsaid.localization.translate.TranslationException;
import com.airsaid.localization.translate.lang.Lang;
import com.airsaid.localization.translate.lang.Languages;
import com.airsaid.localization.translate.services.TranslatorService;
import com.airsaid.localization.utils.TextUtil;
import com.google.common.util.concurrent.AtomicDouble;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagChild;
import com.intellij.psi.xml.XmlTagValue;
import com.intellij.psi.xml.XmlText;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author airsaid
 */
public class TranslateTask extends Task.Backgroundable {

  private static final String NAME_TAG_STRING = "string";
  private static final String NAME_TAG_PLURALS = "plurals";
  private static final String NAME_TAG_STRING_ARRAY = "string-array";

  private static final Logger LOG = Logger.getInstance(TranslateTask.class);

  private final List<Lang> mToLanguages;
  private final List<PsiElement> mValues;
  private final VirtualFile mValueFile;
  private final TranslatorService mTranslatorService;
  private final AndroidValuesService mValueService;

  private OnTranslateListener mOnTranslateListener;
  private TranslationException mTranslationError;

  public interface OnTranslateListener {
    void onTranslateSuccess();

    void onTranslateError(Throwable e);
  }

  public TranslateTask(@Nullable Project project, @Nls @NotNull String title, List<Lang> languages,
                       List<PsiElement> values, PsiFile valueFile) {
    super(project, title);
    mToLanguages = languages;
    mValues = values;
    mValueFile = valueFile.getVirtualFile();
    mTranslatorService = TranslatorService.getInstance();
    mValueService = AndroidValuesService.getInstance();
  }

  /**
   * Set translate result listener.
   *
   * @param listener callback interface. success or fail.
   */
  public void setOnTranslateListener(OnTranslateListener listener) {
    mOnTranslateListener = listener;
  }

  @Override
  public void run(@NotNull ProgressIndicator progressIndicator) {
    boolean isOverwriteExistingString = myProject != null && PropertiesComponent.getInstance(myProject)
        .getBoolean(Constants.KEY_IS_OVERWRITE_EXISTING_STRING);
    LOG.info("run isOverwriteExistingString: " + isOverwriteExistingString);

    for (Lang toLanguage : mToLanguages) {
      if (progressIndicator.isCanceled()) break;

      progressIndicator.setText("Translating to " + toLanguage.getEnglishName() + "...");

      VirtualFile resourceDir = mValueFile.getParent().getParent();
      String valueFileName = mValueFile.getName();
      PsiFile toValuePsiFile = mValueService.getValuePsiFile(myProject, resourceDir, toLanguage, valueFileName);
      LOG.info("Translating language: " + toLanguage.getEnglishName() + ", toValuePsiFile: " + toValuePsiFile);

      File toValueFile;
      List<PsiElement> translatedValues;
      if (toValuePsiFile != null) {
        List<PsiElement> toValues = mValueService.loadValues(toValuePsiFile);
        Map<String, PsiElement> toValuesMap = toValues.stream().collect(Collectors.toMap(
                psiElement -> {
                  if (psiElement instanceof XmlTag)
                    return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                            ((XmlTag) psiElement).getAttributeValue("name"));
                  else return UUID.randomUUID().toString();
                },
                Function.identity()
        ));
        toValueFile = new File(toValuePsiFile.getVirtualFile().getPath());
        translatedValues = doTranslate(progressIndicator, toLanguage, toValuesMap, isOverwriteExistingString);
      } else {
        toValueFile = mValueService.getValueFile(resourceDir, toLanguage, valueFileName);
        translatedValues = doTranslate(progressIndicator, toLanguage, null, isOverwriteExistingString);
      }
      writeTranslatedValues(progressIndicator, toValueFile, translatedValues);
      // If an exception occurs during the translation of the language,
      // the translation of the subsequent languages is terminated.
      // This prevents the loss of successfully translated strings in that language.
      if (mTranslationError != null) {
        throw mTranslationError;
      }
    }
  }

  private List<PsiElement> doTranslate(@NotNull ProgressIndicator progressIndicator,
                                       @NotNull Lang toLanguage,
                                       @Nullable Map<String, PsiElement> toValues,
                                       boolean isOverwrite) {
    LOG.info("doTranslate toLanguage: " + toLanguage.getEnglishName() + ", toValues: " + toValues + ", isOverwrite: " + isOverwrite);
    if (!mValues.isEmpty()) progressIndicator.setFraction(0.5 / mValues.size());

    List<PsiElement> translatedValues = new CopyOnWriteArrayList<>();

    final boolean enableMultiThread = mTranslatorService.isEnableMultiThread();
    final List<CompletableFuture<Void>> futures = enableMultiThread ? new CopyOnWriteArrayList<>() : null;

    for (int i = 0, mValuesSize = mValues.size(); i < mValuesSize; i++) {
      if (progressIndicator.isCanceled()) break;
      PsiElement value = mValues.get(i);

      if (value instanceof XmlTag xmlTag) {
        if (!mValueService.isTranslatable(xmlTag)) {
          translatedValues.add(value);
          continue;
        }

        String name = ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                xmlTag.getAttributeValue("name")
        );
        if (!isOverwrite && toValues != null && toValues.containsKey(name)) {
          translatedValues.add(toValues.get(name));
          continue;
        }

        XmlTag translateValue = ApplicationManager.getApplication().runReadAction((Computable<XmlTag>) () ->
                (XmlTag) xmlTag.copy()
        );
        translatedValues.add(translateValue);
        Runnable r;
        switch (translateValue.getName()) {
          case NAME_TAG_STRING:
            r = () -> translateXmlTag(progressIndicator, toLanguage, translateValue);
            if (enableMultiThread) {
              futures.add(CompletableFuture.runAsync(r));
            } else {
              r.run();
              progressIndicator.setFraction(i / (mValuesSize * 1.0));
            }
            break;
          case NAME_TAG_STRING_ARRAY:
          case NAME_TAG_PLURALS:
            XmlTag[] subTags = ApplicationManager.getApplication()
                    .runReadAction((Computable<XmlTag[]>) translateValue::getSubTags);
            for (XmlTag subTag : subTags) {
              r = () -> translateXmlTag(progressIndicator, toLanguage, subTag);
              if (enableMultiThread) {
                futures.add(CompletableFuture.runAsync(r));
              } else {
                r.run();
                progressIndicator.setFraction(i / (mValuesSize * 1.0));
              }
            }
            break;
        }
      } else {
        translatedValues.add(value);
      }
    }

    if (enableMultiThread) {
      AtomicDouble completedCount = new AtomicDouble(0);
      CountDownLatch mTranslationLatch = new CountDownLatch(futures.size());
      futures.forEach(future -> future.whenComplete((res, ex) -> {
        mTranslationLatch.countDown();
        double fraction = completedCount.updateAndGet(v -> ++v) / futures.size();
        progressIndicator.setFraction(fraction);
      }));
      try {
        mTranslationLatch.await();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    progressIndicator.setFraction(1);

    return filterTranslateFailedValues(progressIndicator,translatedValues);
  }

  private void translateXmlTag(@NotNull ProgressIndicator progressIndicator,
                               @NotNull Lang toLanguage,
                               @NotNull XmlTag xmlTag) {
    if (progressIndicator.isCanceled() || isXliffTag(xmlTag)) return;

    XmlTagValue xmlTagValue = ApplicationManager.getApplication()
        .runReadAction((Computable<XmlTagValue>) xmlTag::getValue);
    XmlTagChild[] children = xmlTagValue.getChildren();
    for (XmlTagChild child : children) {
      if (child instanceof XmlText xmlText) {
          String text = ApplicationManager.getApplication()
            .runReadAction((Computable<String>) xmlText::getValue);
        if (TextUtil.isEmptyOrSpacesLineBreak(text)) {
          continue;
        }
        try {
          String translatedText = mTranslatorService.doTranslate(Languages.AUTO, toLanguage, text);
          ReadAction.run(() -> xmlText.setValue(translatedText));
        } catch (TranslationException e) {
          LOG.warn(e);
          // Just catch the error and wait for that file to be translated and released.
          mTranslationError = e;
          mValueService.markTranslateFailed(xmlTag);
        }
      } else if (child instanceof XmlTag) {
        translateXmlTag(progressIndicator, toLanguage, (XmlTag) child);
      }
    }
  }

  private List<PsiElement> filterTranslateFailedValues(@NotNull ProgressIndicator progressIndicator,
                                                       @NotNull List<PsiElement> translatedValues) {
    LOG.info("filterTranslateFailedValues translatedValues: " + translatedValues);

    List<PsiElement> filteredValues = new ArrayList<>(translatedValues.size());
    boolean removedTag = false;

    for (PsiElement psiElement : translatedValues) {
      if (progressIndicator.isCanceled()) break;

      if (psiElement instanceof XmlTag xmlTag) {
        removedTag = false;

        if (!mValueService.isTranslatable(xmlTag)) {
          filteredValues.add(xmlTag);
          continue;
        }

        if (isXmlTagTranslateFailed(progressIndicator, xmlTag)) {
          removedTag = true;
        } else {
          XmlTagChild[] tagChildren = ReadAction.compute(() -> xmlTag.getValue().getChildren());
          if (tagChildren.length == 0 || ReadAction.compute(xmlTag::getSubTags).length == 0) {
            filteredValues.add(xmlTag);
            continue;
          }

          List<PsiElement> filteredSubChildren = filterTranslateFailedValues(
                  progressIndicator, Arrays.stream(tagChildren).map(child -> ReadAction.compute(child::copy)).toList());

          if (filteredSubChildren.isEmpty()
                  || filteredSubChildren.stream().allMatch(child -> {
            if (child instanceof XmlText t) {
              return StringUtils.isWhitespace(ReadAction.compute(t::getValue));
            }
            return false;
          })) {
            removedTag = true;
          } else {
            ReadAction.run(() -> {
              xmlTag.deleteChildRange(tagChildren[0], tagChildren[tagChildren.length - 1]);
              for (PsiElement subChild : filteredSubChildren) {
                xmlTag.add(subChild);
              }
              filteredValues.add(xmlTag);
            });
          }
        }
      } else {
        if (removedTag) {
          removedTag = false;
          if (!filteredValues.isEmpty()
                  && filteredValues.getLast() instanceof XmlText prevText
                  && psiElement instanceof XmlText curText
                  && StringUtils.isWhitespace(ReadAction.compute(prevText::getValue))
                  && StringUtils.isWhitespace(ReadAction.compute(curText::getValue))
          ) {
            filteredValues.removeLast();
          }
        }
        filteredValues.add(psiElement);
      }
    }

    LOG.info("filtered translated values, res: " + translatedValues);

    return filteredValues;
  }

  private boolean isXmlTagTranslateFailed(@NotNull ProgressIndicator progressIndicator, @NotNull XmlTag xmlTag) {
    if (isXliffTag(xmlTag) || !mValueService.isTranslatable(xmlTag)) return false;
    return progressIndicator.isCanceled() || mValueService.isTranslateFailed(xmlTag);
  }

  private void writeTranslatedValues(@NotNull ProgressIndicator progressIndicator,
                                     @NotNull File valueFile,
                                     @NotNull List<PsiElement> translatedValues) {
    LOG.info("writeTranslatedValues valueFile: " + valueFile + ", translatedValues: " + translatedValues);

    if (progressIndicator.isCanceled() || translatedValues.isEmpty()) return;

    progressIndicator.setText("Writing to " + valueFile.getParentFile().getName() + " data...");
    mValueService.writeValueFile(translatedValues, valueFile);

    refreshAndOpenFile(valueFile);
  }

  private void refreshAndOpenFile(File file) {
    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
    boolean isOpenTranslatedFile = myProject != null && PropertiesComponent.getInstance(myProject)
        .getBoolean(Constants.KEY_IS_OPEN_TRANSLATED_FILE);
    if (virtualFile != null && isOpenTranslatedFile) {
      ApplicationManager.getApplication().invokeLater(() ->
          FileEditorManager.getInstance(myProject).openFile(virtualFile, true));
    }
  }

  private boolean isXliffTag(XmlTag xmlTag) {
    return xmlTag != null && "xliff:g".equals(ReadAction.compute(xmlTag::getName));
  }

  @Override
  public void onSuccess() {
    super.onSuccess();
    translateSuccess();
  }

  @Override
  public void onThrowable(@NotNull Throwable error) {
    super.onThrowable(error);
    translateError(error);
  }

  private void translateSuccess() {
    if (mOnTranslateListener != null) {
      mOnTranslateListener.onTranslateSuccess();
    }
  }

  private void translateError(Throwable error) {
    if (mOnTranslateListener != null) {
      mOnTranslateListener.onTranslateError(error);
    }
  }
}
