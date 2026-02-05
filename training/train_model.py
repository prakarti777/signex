
import numpy as np
import os
import tensorflow as tf
from tensorflow import keras
from keras import layers
import matplotlib.pyplot as plt
import json
import argparse

# Config
SEQ_LENGTH = 30
INPUT_DIM = 171
BATCH_SIZE = 32
EPOCHS = 50

def build_model(num_classes):
    """
    Builds an advanced architecture for Sign Language Recognition.
    Features: Residual connections, Spatial Dropout, and Multi-Head Attention.
    """
    inputs = layers.Input(shape=(SEQ_LENGTH, INPUT_DIM))
    
    # 1. Feature Normalization & Projection
    # Project 171 dims to a higher dimensional space for better feature extraction
    x = layers.Dense(256, activation='gelu')(inputs)
    x = layers.BatchNormalization()(x)
    
    # 2. Temporal Feature Extraction (Conv1D Stack)
    # Using 'same' padding to maintain temporal resolution for skip connections
    res = layers.Conv1D(256, kernel_size=3, padding='same', activation='gelu')(x)
    x = layers.Add()([x, res]) # Skip connection
    x = layers.SpatialDropout1D(0.2)(x)
    
    # 3. Recurrent Temporal Logic (Bidirectional GRU)
    # GRU is often more efficient than LSTM for TFLite on mobile
    x = layers.Bidirectional(layers.GRU(128, return_sequences=True))(x)
    x = layers.BatchNormalization()(x)
    
    # 4. Global Context (Self-Attention)
    # Allows the model to weigh different frames in the 30-frame sequence
    attn_out = layers.MultiHeadAttention(num_heads=4, key_dim=64)(x, x)
    x = layers.LayerNormalization()(attn_out + x) # Residual Attention
    
    # 5. Pooling & Classification Head
    x = layers.GlobalAveragePooling1D()(x)
    
    x = layers.Dense(512, activation='gelu')(x)
    x = layers.BatchNormalization()(x)
    x = layers.Dropout(0.4)(x)
    
    x = layers.Dense(256, activation='gelu', kernel_regularizer=keras.regularizers.l2(0.001))(x)
    x = layers.Dropout(0.3)(x)
    
    outputs = layers.Dense(num_classes, activation='softmax')(x)
    
    model = keras.Model(inputs=inputs, outputs=outputs)
    return model

def plot_history(history):
    acc = history.history['accuracy']
    val_acc = history.history.get('val_accuracy', [])
    loss = history.history['loss']
    val_loss = history.history.get('val_loss', [])
    
    plt.figure(figsize=(12, 5))
    plt.subplot(1, 2, 1)
    plt.plot(acc, label='Train Acc')
    if val_acc: plt.plot(val_acc, label='Val Acc')
    plt.legend()
    plt.title('Accuracy')
    
    plt.subplot(1, 2, 2)
    plt.plot(loss, label='Train Loss')
    if val_loss: plt.plot(val_loss, label='Val Loss')
    plt.legend()
    plt.title('Loss')
    plt.savefig('training_history.png')

def main(data_path, model_save_path):
    # 1. Load Data
    print(f"Loading data from {data_path}...")
    X = np.load(os.path.join(data_path, 'X.npy'))
    y = np.load(os.path.join(data_path, 'y.npy'))
    
    # Load labels
    with open(os.path.join(data_path, 'label_map.json'), 'r') as f:
        label_map = json.load(f)
    classes = list(label_map.keys())
    NUM_CLASSES = len(classes)
    
    print(f"Loaded {X.shape[0]} samples with {NUM_CLASSES} classes.")
    
    # Convert labels to categorical
    y = keras.utils.to_categorical(y, NUM_CLASSES)
    
    # 2. Advanced Augmentation (In-Memory Jitter, Rotation, Mirroring)
    X_original = X.copy()
    y_original = y.copy()
    
    X_aug_list = []
    y_aug_list = []
    
    # a. Gaussian Jitter (simulate sensor noise)
    noise = np.random.normal(0, 0.003, X.shape)
    X_aug_list.append(X + noise)
    y_aug_list.append(y)
    
    # b. 90-Degree Rotation (Simulate portrait/landscape mismatch)
    # (x, y) -> (y, 1-x)
    X_rot = X.copy()
    X_rot[:, :, 0::3] = X[:, :, 1::3] # new x = old y
    X_rot[:, :, 1::3] = 1.0 - X[:, :, 0::3] # new y = 1 - old x
    X_aug_list.append(X_rot)
    y_aug_list.append(y)
    
    # c. Front-Camera Mirroring Augmentation (Already in dataprep, but reinforcing here)
    # (x, y) -> (1-x, y)
    X_mirror = X.copy()
    X_mirror[:, :, 0::3] = 1.0 - X[:, :, 0::3]
    X_aug_list.append(X_mirror)
    y_aug_list.append(y)
    
    # d. Missing-Frame Augmentation (Simulate tracking loss/occlusion)
    # Randomly zero out 1-3 frames in some sequences
    X_missing = X.copy()
    for i in range(len(X_missing)):
        if np.random.random() > 0.5:
            num_missing = np.random.randint(1, 4)
            indices = np.random.choice(range(SEQ_LENGTH), num_missing, replace=False)
            X_missing[i, indices, :] = 0
    X_aug_list.append(X_missing)
    y_aug_list.append(y)
    
    X = np.concatenate([X_original] + X_aug_list, axis=0)
    y = np.concatenate([y_original] + y_aug_list, axis=0)
    
    # 3. Split
    from sklearn.model_selection import train_test_split
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.15, random_state=42)
    
    print(f"Training on {X_train.shape[0]} samples. Validation: {X_test.shape[0]}")
    
    # 4. Build & Train
    model = build_model(NUM_CLASSES)
    
    # Higher initial LR with weight decay
    optimizer = keras.optimizers.AdamW(learning_rate=1e-3, weight_decay=1e-4)
    model.compile(optimizer=optimizer, 
                  loss='categorical_crossentropy', 
                  metrics=['accuracy'])
    
    model.summary()
    
    # Better callbacks
    callbacks = [
        keras.callbacks.EarlyStopping(monitor='val_accuracy', patience=15, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor='val_loss', patience=7, factor=0.5, min_lr=1e-6),
        keras.callbacks.ModelCheckpoint(filepath='best_model.keras', monitor='val_accuracy', save_best_only=True)
    ]
    
    history = model.fit(
        X_train, y_train,
        validation_data=(X_test, y_test),
        epochs=100, # Increased epochs
        batch_size=BATCH_SIZE,
        callbacks=callbacks
    )
    
    plot_history(history)
    
    # 5. TFLite Export (Optimized for Mobile)
    print("Exporting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [
      tf.lite.OpsSet.TFLITE_BUILTINS, # Enable TensorFlow Lite ops.
      tf.lite.OpsSet.SELECT_TF_OPS # Enable TensorFlow ops.
    ]
    # Use float16 quantization for better mobile performance if accuracy allows
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    
    tflite_path = os.path.join(model_save_path, 'model.tflite')
    with open(tflite_path, 'wb') as f:
        f.write(tflite_model)
        
    print(f"Model saved to {tflite_path}")
    
    # Save Label Mapping for App
    with open(os.path.join(model_save_path, 'label_mapping2.txt'), 'w') as f:
        sorted_labels = sorted(label_map.items(), key=lambda item: item[1])
        for label, idx in sorted_labels:
            f.write(f"{label},{idx}\n")
            
    print("Label mapping saved.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', required=True, help='Path to folder with X.npy, y.npy')
    parser.add_argument('--save_path', required=True, help='Output folder')
    args = parser.parse_args()
    
    os.makedirs(args.save_path, exist_ok=True)
    main(args.data, args.save_path)
