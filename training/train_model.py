
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
    Builds the exact architecture expected by Android.
    Input: [None, 30, 171]
    """
    inputs = layers.Input(shape=(SEQ_LENGTH, INPUT_DIM))
    
    # 1. Feature Extraction
    x = layers.Conv1D(64, kernel_size=3, padding='valid', activation='gelu')(inputs)
    x = layers.BatchNormalization()(x) # Critical: Learnable Normalization
    
    # 2. Temporal Logic
    x = layers.Bidirectional(layers.LSTM(128, return_sequences=True))(x)
    
    # 3. Attention Mechanism
    # Self-attention for weighting frames
    attn_out = layers.MultiHeadAttention(num_heads=2, key_dim=64)(x, x)
    x = layers.LayerNormalization()(attn_out + x) # Skip connection
    
    # 4. Classification Head
    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dense(256, activation='silu', kernel_regularizer=keras.regularizers.l2(0.01))(x)
    x = layers.Dropout(0.4)(x)
    
    outputs = layers.Dense(num_classes, activation='softmax')(x)
    
    model = keras.Model(inputs=inputs, outputs=outputs)
    return model

def plot_history(history):
    acc = history.history['accuracy']
    val_acc = history.history['val_accuracy']
    loss = history.history['loss']
    val_loss = history.history['val_loss']
    
    plt.figure(figsize=(10, 5))
    plt.subplot(1, 2, 1)
    plt.plot(acc, label='Train Acc')
    plt.plot(val_acc, label='Val Acc')
    plt.legend()
    plt.title('Accuracy')
    
    plt.subplot(1, 2, 2)
    plt.plot(loss, label='Train Loss')
    plt.plot(val_loss, label='Val Loss')
    plt.legend()
    plt.title('Loss')
    plt.savefig('training_history.png')
    plt.show()

def main(data_path, model_save_path):
    # 1. Load Data
    X = np.load(os.path.join(data_path, 'X.npy'))
    y = np.load(os.path.join(data_path, 'y.npy'))
    
    # Load labels
    with open(os.path.join(data_path, 'label_map.json'), 'r') as f:
        label_map = json.load(f)
    classes = list(label_map.keys())
    NUM_CLASSES = len(classes)
    
    # Convert labels to categorical
    y = keras.utils.to_categorical(y, NUM_CLASSES).astype(int)
    
    # Split
    from sklearn.model_selection import train_test_split
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.1, random_state=42)
    
    print(f"Training on {X_train.shape[0]} samples. Validation: {X_test.shape[0]}")
    
    # 2. Build & Train
    model = build_model(NUM_CLASSES)
    model.compile(optimizer=keras.optimizers.AdamW(learning_rate=3e-4, weight_decay=1e-5), 
                  loss='categorical_crossentropy', 
                  metrics=['accuracy'])
    
    model.summary()
    
    history = model.fit(
        X_train, y_train,
        validation_data=(X_test, y_test),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        callbacks=[
            keras.callbacks.EarlyStopping(patience=10, restore_best_weights=True),
            keras.callbacks.ReduceLROnPlateau(patience=5, factor=0.5)
        ]
    )
    
    plot_history(history)
    
    # 3. Save Keras Model
    model.save('model.keras')
    
    # 4. TFLite Export (FROZEN BATCHNORM IMPLICIT)
    print("Exporting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [
      tf.lite.OpsSet.TFLITE_BUILTINS, # Enable TensorFlow Lite ops.
      tf.lite.OpsSet.SELECT_TF_OPS # Enable TensorFlow ops.
    ]
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    
    with open(os.path.join(model_save_path, 'model.tflite'), 'wb') as f:
        f.write(tflite_model)
        
    print(f"Model saved to {os.path.join(model_save_path, 'model.tflite')}")
    
    # 5. Export Label File (Compatible with Android app)
    # Format: "Label, Index" (although app handles just "Label")
    # We stick to single line per label strictly ordered by index 0..N
    with open(os.path.join(model_save_path, 'label_mapping2.txt'), 'w') as f:
        # Sort by index
        sorted_labels = sorted(label_map.items(), key=lambda item: item[1])
        for label, idx in sorted_labels:
            f.write(f"{label},{idx}\n")
            
    print("Label mapping saved.")

if __name__ == "__main__":
    # Example usage: python train_model.py --data ./processed --save_path ./models
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', required=True, help='Path to folder with X.npy, y.npy')
    parser.add_argument('--save_path', required=True, help='Output folder')
    args = parser.parse_args()
    
    os.makedirs(args.save_path, exist_ok=True)
    main(args.data, args.save_path)
