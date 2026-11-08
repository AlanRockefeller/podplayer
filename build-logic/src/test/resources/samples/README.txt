Bad and good samples for the guardrail tests.

They end in ".sample" so that none of the real checks ever scans them (the checks only look at
.kt, .kts, .java, .aidl and .xml files). The tests read them as resources, and the functional tests
copy them into throwaway projects under their real extension.
