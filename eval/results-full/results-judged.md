# Stance-judge summary

Substantive correctness — does the pipeline's answer convey the document's actual stance? Independent of role-tag labelling.

| pipeline | kind | expected | n | yes | partial | no | yes_rate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| anchor | control | ASSERTS | 9 | 7 | 0 | 2 | 78% |
| anchor | trap | REJECTS | 25 | 21 | 0 | 4 | 84% |
| vanilla | control | ASSERTS | 9 | 3 | 0 | 6 | 33% |
| vanilla | trap | REJECTS | 25 | 12 | 0 | 13 | 48% |

## anchor / trap / REJECTS — per paper

| paper | n | yes | partial | no | yes_rate |
| --- | --- | --- | --- | --- | --- |
| bell-shallit-2022-dombi | 4 | 4 | 0 | 0 | 100% |
| cranston-rabern-2016-steinberg | 4 | 3 | 0 | 1 | 75% |
| cranston-rabern-steiner-2022-woodall | 4 | 4 | 0 | 0 | 100% |
| duval-goeckner-klivans-martin-2015-partitionability | 4 | 4 | 0 | 0 | 100% |
| gladkov-pak-zimin-2024-bunkbed | 4 | 3 | 0 | 1 | 75% |
| wagner-2019-lp-refutation | 5 | 3 | 0 | 2 | 60% |
