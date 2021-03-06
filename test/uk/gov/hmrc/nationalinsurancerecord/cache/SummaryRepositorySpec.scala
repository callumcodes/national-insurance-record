/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.nationalinsurancerecord.cache

import org.joda.time.LocalDate
import org.mockito.{Matchers, Mockito}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneAppPerSuite
import reactivemongo.api.indexes.CollectionIndexesManager
import reactivemongo.json.collection.JSONCollection
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.nationalinsurancerecord.NationalInsuranceRecordUnitSpec
import uk.gov.hmrc.nationalinsurancerecord.domain.APITypes
import uk.gov.hmrc.nationalinsurancerecord.domain.nps.NpsSummary
import uk.gov.hmrc.nationalinsurancerecord.services.{CachingMongoService, MetricsService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SummaryRepositorySpec extends NationalInsuranceRecordUnitSpec with OneAppPerSuite with MongoSpecSupport with MockitoSugar {
  // scalastyle:off magic.number

  val testSummaryModel = NpsSummary(rreToConsider = false, dateOfDeath = None,
    earningsIncludedUpTo = new LocalDate(2014, 4, 5), dateOfBirth = new LocalDate(1952, 11, 21), finalRelevantYear = 2016
  )

  "SummaryMongoService" should {
    val mockMetrics = mock[MetricsService]
    val nino = generateNino()
    val service = new CachingMongoService[SummaryCache, NpsSummary](SummaryCache.formats, SummaryCache.apply,
      APITypes.Summary, StubApplicationConfig, mockMetrics) {
      override val timeToLive = 30
    }

    "persist a SummaryModel in the repo" in {
      reset(mockMetrics)
      val resultF = service.insertByNino(nino, testSummaryModel)
      await(resultF) shouldBe true
      verify(mockMetrics, Mockito.atLeastOnce()).cacheWritten()
    }

    "find a SummaryModel in the repo" in {
      reset(mockMetrics)
      val resultF = service.findByNino(nino)
      resultF.get shouldBe testSummaryModel
      verify(mockMetrics, Mockito.atLeastOnce()).cacheRead()
      verify(mockMetrics, Mockito.atLeastOnce()).cacheReadFound()
    }

    "return None when there is nothing in the repo" in {
      reset(mockMetrics)
      val resultF = service.findByNino(generateNino())
      await(resultF) shouldBe None
      verify(mockMetrics, Mockito.atLeastOnce()).cacheRead()
      verify(mockMetrics, Mockito.atLeastOnce()).cacheReadNotFound()
    }

    "return None when there is a Mongo error" in {
      import scala.concurrent.ExecutionContext.Implicits.global

      val stubCollection = mock[JSONCollection]
      val stubIndexesManager = mock[CollectionIndexesManager]

      when(stubCollection.indexesManager).thenReturn(stubIndexesManager)

      class TestSummaryMongoService extends CachingMongoService[SummaryCache, NpsSummary](
        SummaryCache.formats, SummaryCache.apply, APITypes.Summary, StubApplicationConfig, mockMetrics) {
        override lazy val collection = stubCollection
        override val timeToLive = 30
      }
      reset(mockMetrics)
      when(stubCollection.find(Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
      when(stubCollection.indexesManager.ensure(Matchers.any())).thenReturn(Future.successful(true))

      val testRepository = new TestSummaryMongoService

      val found = await(testRepository.findByNino(generateNino()))
      found shouldBe None
      verify(mockMetrics, Mockito.atLeastOnce()).cacheRead()
      verify(mockMetrics, Mockito.atLeastOnce()).cacheReadNotFound()
    }

    "multiple calls to insertByNino should be fine (upsert)" in {
      await(service.insertByNino(generateNino(), testSummaryModel)) shouldBe true
      await(service.insertByNino(generateNino(), testSummaryModel)) shouldBe true
    }

  }

}
