package com.example

import com.example.data.model.ScrollPlatform
import com.example.service.extractor.InstagramIdentityExtractor
import com.example.service.extractor.YouTubeIdentityExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM unit tests for extractor strategies.
 */
class ExampleUnitTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun verify_extractor_platforms_and_package_names() {
    val igExtractor = InstagramIdentityExtractor()
    val ytExtractor = YouTubeIdentityExtractor()

    assertEquals(ScrollPlatform.INSTAGRAM, igExtractor.platform)
    assertEquals("com.instagram.android", igExtractor.targetPackageName)

    assertEquals(ScrollPlatform.YOUTUBE, ytExtractor.platform)
    assertEquals("com.google.android.youtube", ytExtractor.targetPackageName)
  }
}
