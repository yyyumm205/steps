package com.nexthci.ringfitness

/** Explicit development entry; production builds do not include this Activity or runtime. */
class DemoCollectionActivity : StepCollectionActivity() {
    override fun provideFlow(): CollectionFlow = DemoFlowRuntime.get(applicationContext)
}
