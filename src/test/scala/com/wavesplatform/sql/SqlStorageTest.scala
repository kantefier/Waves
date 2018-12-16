package com.wavesplatform.sql

import java.util.concurrent.ThreadLocalRandom

import com.wavesplatform.BlockGen
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.block.Block
import com.wavesplatform.crypto.KeyLength
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.SqlDb
import com.wavesplatform.transaction.GenesisTransaction
import monix.execution.Scheduler.fixedPool
import org.scalatest.{FreeSpec, Matchers}

class SqlStorageTest extends FreeSpec with Matchers with BlockGen {

  "sql db test" - {
    implicit val ec = fixedPool("miner-pool", poolSize = 20, reporter = println)
    val db          = new SqlDb(TestFunctionalitySettings.Enabled)

    "init succcessful" in {
      println("success")
    }

    "test insert block" in {
      val master, recipient = randomPrivateKeyAccount()
      val block             = getTwoMinersBlockChain(master, recipient, 0).head
      db.insertBlock(block, 1, 5)
    }
  }

  private val signerA, signerB = randomPrivateKeyAccount()

  def randomPrivateKeyAccount(): PrivateKeyAccount = {
    val seed = Array.ofDim[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    PrivateKeyAccount(seed)
  }

  private def getTwoMinersBlockChain(from: PrivateKeyAccount, to: PrivateKeyAccount, numPayments: Int): Seq[Block] = {
    val ts                   = System.currentTimeMillis() - 100000
    val genesisTx            = GenesisTransaction.create(from, Long.MaxValue - 1, ts).right.get
    val features: Set[Short] = Set[Short](2)

//    val paymentTxs = (1 to numPayments).map { i =>
//      createWavesTransfer(
//        from,
//        to,
//        amount = 10000,
//        0,
//        timestamp = ts + i * 1000
//      ).explicitGet()
//    }

    (genesisTx +: Nil).zipWithIndex.map {
      case (x, i) =>
        val signer = if (i % 2 == 0) signerA else signerB
        TestBlock.create(signer, Seq(x), features)
    }
  }
}
