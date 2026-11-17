// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

fun main(args: Array<String>) {
    // 签名与设备注册都在本机完成；--signer 仅为兼容旧命令行保留，传入也不再生效。
    val signer = RemoteSigner()
    val repository=ContentRepository(signer=signer)
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    // 解析 --keyword 的值，没有时用默认剧名；其余位置参数留给剧集 ID。
    val keywordIndex=args.indexOf("--keyword")
    val keyword=args.getOrNull(keywordIndex+1)
        ?.takeIf { keywordIndex>=0 && !it.startsWith("--") }
        ?: if(type==ContentType.COMIC) "修仙" else "总裁"
    val positional=args.withIndex().filterNot { (i,arg) -> arg.startsWith("--") || i==keywordIndex+1 }.map { it.value }
    val started=System.currentTimeMillis()
    // --stream-only：只验证详情与播放链路，跳过搜索和首页，避免内容接口偶发故障挡住播放排查。
    val streamOnly=args.contains("--stream-only")
    val seriesArg=positional.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }
    println("本机注册设备 device=${runCatching { signer.profile().deviceId }.getOrElse { "不可用：" + it.message }}")
    if(!streamOnly) {
        val search=repository.search(keyword,type=type); println("searchMs=${System.currentTimeMillis()-started}; search=${search.items.size}; hasMore=${search.hasMore}")
        if(type==ContentType.COMIC && search.hasMore) {
            val next=repository.search(keyword,2,type)
            println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
        val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
        if(type==ContentType.COMIC) {
            val next=repository.home(2,type)
            println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
    }
    val detail=repository.detail(seriesArg ?: repository.home(type=type).items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    // --warm：验证「一次注册问一整窗」是否可用，以及切集是否真的命中缓存（耗时接近 0ms）。
    if(args.contains("--warm")) {
        val ids=detail.episodes.take(15)
        val primary=repository.stream(ids.first())
        println("primary quality=${primary.quality}")
        val startedWarm=System.currentTimeMillis()
        val report=repository.warm(ids)
        println("warm requested=${report.requested} cached=${report.cached} batch=${report.batch} route=${report.route} ms=${System.currentTimeMillis()-startedWarm}")
        val startedHit=System.currentTimeMillis()
        val hit=repository.stream(ids.getOrElse(5) { ids.last() })
        println("cacheHit quality=${hit.quality} ms=${System.currentTimeMillis()-startedHit}")
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        return
    }
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
