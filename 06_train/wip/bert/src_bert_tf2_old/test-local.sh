SM_CURRENT_HOST=blah SM_NUM_GPUS=0 SM_HOSTS={\"hosts\":\"blah\"} SM_CHANNEL_TRAIN=data/train SM_CHANNEL_VALIDATION=data/validation SM_MODEL_DIR=. python tf_bert_reviews.py # TODO: --model-type=bert --model-name=bert-base-uncased 

rm uncased_L-12_H-768_A-12.zip
rm -rf uncased_L-12_H-768_A-12
